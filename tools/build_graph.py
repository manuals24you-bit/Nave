#!/usr/bin/env python3
"""
Buduje dane nawigacyjne Drogowskazu z pliku OpenStreetMap (.osm.pbf):

  roads.bin   - graf drogowy (węzły, odcinki z geometrią, limity prędkości,
                jednokierunkowość, ronda, nazwy) + siatka przestrzenna do
                przyciągania pozycji GPS do drogi
  search.tsv  - indeks wyszukiwania (miejscowości i ulice)

Użycie:
  python3 build_graph.py wejscie.osm.pbf katalog_wyjsciowy

Najlepiej najpierw odfiltrować plik osmium-tool (patrz workflow build-data.yml),
wtedy przetwarzanie całej Polski zajmuje kilka-kilkanaście minut.
"""
import argparse
import math
import os
import struct
import sys
from array import array

import numpy as np
import osmium

# Kolejność MUSI odpowiadać RoadClass w aplikacji (RoadGraph.kt)
CLASSES = [
    "motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link",
    "secondary", "secondary_link", "tertiary", "tertiary_link", "unclassified",
    "residential", "living_street", "service", "road",
    # tylko dla pieszych / rowerów (domyślnie bez samochodów)
    "track", "pedestrian", "footway", "cycleway", "path", "steps", "bridleway",
]
CLASS_IDX = {c: i for i, c in enumerate(CLASSES)}

# Bity atrybutów odcinka (edgeAttr)
F_FWD_ONLY = 1 << 5     # można jechać tylko from->to
F_BWD_ONLY = 1 << 6     # można jechać tylko to->from
F_ROUNDABOUT = 1 << 7
F_TOLL = 1 << 24
F_TUNNEL = 1 << 25
F_BIKE_DESIGNATED = 1 << 26  # droga/ścieżka rowerowa
F_CAR_NO = 1 << 27
F_BIKE_NO = 1 << 28
F_FOOT_NO = 1 << 29
F_BIKE_ONEWAY_FREE = 1 << 30  # rower może jechać pod prąd (kontraruch)
F_UNPAVED = -(1 << 31)  # bit 31 (int32 ze znakiem)

YES = ("yes", "designated", "permissive", "destination", "official")
NO = ("no", "private", "agricultural", "forestry", "delivery", "use_sidepath", "discouraged")
UNPAVED = {"unpaved", "gravel", "fine_gravel", "dirt", "earth", "ground", "grass", "sand", "mud",
           "compacted", "pebblestone", "woodchips", "rock"}

PLACE_TYPES = {"city": "miasto", "town": "miasto", "village": "wieś", "suburb": "dzielnica",
               "quarter": "osiedle", "neighbourhood": "osiedle", "hamlet": "przysiółek"}

CELL_DEG = 0.005  # ok. 550 m


def parse_speed(v):
    """Zwraca limit w km/h, 0 = brak/nieznany."""
    if not v:
        return 0
    v = v.strip().lower()
    table = {
        "pl:urban": 50, "pl:rural": 90, "pl:expressway": 120, "pl:motorway": 140,
        "pl:living_street": 20, "pl:zone30": 30, "pl:zone20": 20, "pl:zone:30": 30,
        "pl:30": 30, "pl:20": 20, "walk": 10, "pl:walk": 10,
    }
    if v in table:
        return table[v]
    if v.startswith("pl:zone"):
        digits = "".join(ch for ch in v if ch.isdigit())
        return int(digits) if digits else 0
    try:
        if v.endswith("mph"):
            return int(round(float(v[:-3].strip()) * 1.609))
        if ";" in v:  # wiele wartości - bierzemy najniższą
            vals = [parse_speed(x) for x in v.split(";")]
            vals = [x for x in vals if x > 0]
            return min(vals) if vals else 0
        n = int(round(float(v.split()[0])))
        return n if 5 <= n <= 250 else 0
    except ValueError:
        return 0


def way_maxspeeds(tags, hw):
    base = parse_speed(tags.get("maxspeed"))
    if base == 0:
        base = parse_speed(tags.get("maxspeed:type")) or parse_speed(tags.get("source:maxspeed")) \
            or parse_speed(tags.get("zone:maxspeed"))
    fwd = parse_speed(tags.get("maxspeed:forward")) or base
    bwd = parse_speed(tags.get("maxspeed:backward")) or base
    if hw == "motorway":
        fwd = fwd or 140
        bwd = bwd or 140
    elif hw == "living_street":
        fwd = fwd or 20
        bwd = bwd or 20
    return min(fwd, 255), min(bwd, 255)


class Collector(osmium.SimpleHandler):
    def __init__(self):
        super().__init__()
        self.refs = array("q")
        self.lat = array("i")
        self.lon = array("i")
        self.way_start = array("q")
        self.way_attr = array("i")
        self.way_name = array("i")
        self.names = {}
        self.name_list = []
        self.way_id = array("q")
        self.restrictions = []  # (wartość, from_way, via_node, to_way)
        self.hazards = []       # (rodzaj, lat, lon, limit)
        self.places = []  # (name, type, lat, lon)
        self.street_points = []  # (name_id, lat, lon)

    def name_id(self, s):
        if not s:
            return -1
        i = self.names.get(s)
        if i is None:
            i = len(self.name_list)
            self.names[s] = i
            self.name_list.append(s)
        return i

    def relation(self, r):
        t = r.tags
        if t.get("type") != "restriction":
            return
        val = t.get("restriction")
        if not val or not (val.startswith("no_") or val.startswith("only_")):
            return
        exc = t.get("except", "")
        if "motorcar" in exc or "motor_vehicle" in exc or "vehicle" in exc:
            return
        fw = tw = via = None
        for m in r.members:
            if m.role == "from" and m.type == "w":
                fw = m.ref
            elif m.role == "to" and m.type == "w":
                tw = m.ref
            elif m.role == "via":
                if m.type == "n":
                    via = m.ref
                else:
                    return  # ograniczenia przez drogę (via=way) pomijamy
        if fw is not None and tw is not None and via is not None:
            self.restrictions.append((val, fw, via, tw))

    def node(self, n):
        tg = n.tags
        if tg.get("highway") == "speed_camera" or tg.get("enforcement") == "maxspeed":
            if n.location.valid():
                self.hazards.append(("camera", n.location.lat, n.location.lon, parse_speed(tg.get("maxspeed"))))
        elif tg.get("railway") == "level_crossing":
            if n.location.valid():
                self.hazards.append(("crossing", n.location.lat, n.location.lon, 0))
        p = n.tags.get("place")
        if p in PLACE_TYPES:
            nm = n.tags.get("name")
            if nm and n.location.valid():
                self.places.append((nm, p, n.location.lat, n.location.lon))

    def way(self, w):
        t = w.tags
        hw = t.get("highway")
        if hw not in CLASS_IDX:
            return
        if t.get("area") == "yes":
            return
        if t.get("access") == "private" or t.get("service") == "emergency_access":
            return
        cls = CLASS_IDX[hw]
        general = t.get("access")
        motor = t.get("motor_vehicle") or t.get("motorcar") or t.get("vehicle") or general
        bike = t.get("bicycle") or t.get("vehicle") or general
        foot = t.get("foot") or general
        motorroad = t.get("motorroad") == "yes" or hw in ("motorway", "motorway_link")

        car_ok = cls <= CLASS_IDX["road"] and motor not in NO
        if hw == "track":
            car_ok = motor in YES
        bike_ok = not motorroad and bike not in NO and hw not in ("steps",)
        if hw in ("footway", "pedestrian", "bridleway"):
            bike_ok = bike in YES
        foot_ok = not motorroad and foot not in NO
        if hw == "cycleway" or hw == "bridleway":
            foot_ok = foot in YES or t.get("segregated") is not None
        if not (car_ok or bike_ok or foot_ok):
            return

        pts = []
        for n in w.nodes:
            loc = n.location
            if not loc.valid():
                return  # niekompletna droga (brzeg wycinka) - pomijamy
            pts.append((n.ref, loc.y, loc.x))  # y/x = współrzędne * 1e7 (int)
        if len(pts) < 2:
            return

        attr = cls
        if not car_ok:
            attr |= F_CAR_NO
        if not bike_ok:
            attr |= F_BIKE_NO
        if not foot_ok:
            attr |= F_FOOT_NO
        if hw == "cycleway" or bike == "designated" or t.get("cycleway") in ("lane", "track") \
                or t.get("cycleway:right") in ("lane", "track") or t.get("cycleway:both") in ("lane", "track"):
            attr |= F_BIKE_DESIGNATED
        if t.get("oneway:bicycle") == "no" or (t.get("cycleway") or "").startswith("opposite"):
            attr |= F_BIKE_ONEWAY_FREE
        surf = t.get("surface")
        if surf in UNPAVED or (hw == "track" and surf is None and t.get("tracktype") not in ("grade1",)):
            attr |= F_UNPAVED
        oneway = t.get("oneway")
        junction = t.get("junction")
        if junction in ("roundabout", "circular"):
            attr |= F_ROUNDABOUT
        if oneway in ("yes", "true", "1"):
            attr |= F_FWD_ONLY
        elif oneway in ("-1", "reverse"):
            attr |= F_BWD_ONLY
        elif oneway != "no" and (hw in ("motorway", "motorway_link") or junction in ("roundabout", "circular")):
            attr |= F_FWD_ONLY
        if t.get("toll") == "yes":
            attr |= F_TOLL
        if t.get("tunnel") in ("yes", "building_passage"):
            attr |= F_TUNNEL
        if t.get("bridge") == "yes":
            attr |= F_BRIDGE
        mf, mb = way_maxspeeds(t, hw)
        attr |= (mf & 0xFF) << 8
        attr |= (mb & 0xFF) << 16

        nm = t.get("name") or ""
        ref = t.get("ref") or ""
        label = nm if nm else ref
        if nm and ref and hw in ("motorway", "trunk", "primary"):
            label = f"{ref} {nm}"
        nid = self.name_id(label)

        self.way_id.append(w.id)
        self.way_start.append(len(self.refs))
        self.way_attr.append(attr)
        self.way_name.append(nid)
        for ref_, y, x in pts:
            self.refs.append(ref_)
            self.lat.append(y)
            self.lon.append(x)

        if nm and car_ok and hw not in ("motorway", "motorway_link", "trunk_link", "service"):
            mid = pts[len(pts) // 2]
            self.street_points.append((nm, mid[1] / 1e7, mid[2] / 1e7))


def build(pbf, outdir):
    os.makedirs(outdir, exist_ok=True)
    c = Collector()
    print("Wczytywanie OSM...", flush=True)
    c.apply_file(pbf, locations=True, idx="flex_mem")

    refs = np.frombuffer(c.refs, dtype=np.int64)
    lat = np.frombuffer(c.lat, dtype=np.int32)
    lon = np.frombuffer(c.lon, dtype=np.int32)
    P = len(refs)
    W = len(c.way_start)
    if W == 0:
        sys.exit("Brak dróg w pliku wejściowym")
    way_start = np.frombuffer(c.way_start, dtype=np.int64)
    way_end = np.append(way_start[1:], P)  # wyłącznie
    way_attr = np.frombuffer(c.way_attr, dtype=np.int32)
    way_name = np.frombuffer(c.way_name, dtype=np.int32)
    print(f"Drogi: {W}, punkty: {P}", flush=True)

    # Numer drogi dla każdej pozycji
    way_of = np.repeat(np.arange(W, dtype=np.int64), way_end - way_start)

    # Węzły skrzyżowań: użyte >1 raz, końce dróg oraz węzły "via" zakazów skrętu
    uniq, inv, cnt = np.unique(refs, return_inverse=True, return_counts=True)
    is_j = cnt[inv] >= 2
    if c.restrictions:
        via_arr = np.array(sorted({r[2] for r in c.restrictions}), dtype=np.int64)
        is_j |= np.isin(refs, via_arr)
    is_j[way_start] = True
    is_j[way_end - 1] = True
    J = np.nonzero(is_j)[0]

    # Odcinki = kolejne pozycje-skrzyżowania w obrębie tej samej drogi
    same = way_of[J[:-1]] == way_of[J[1:]]
    ea = J[:-1][same]
    eb = J[1:][same]
    E = len(ea)

    # Węzły grafu
    jrefs = np.unique(refs[J])
    N = len(jrefs)
    e_from = np.searchsorted(jrefs, refs[ea]).astype(np.int32)
    e_to = np.searchsorted(jrefs, refs[eb]).astype(np.int32)
    node_lat = np.zeros(N, dtype=np.int32)
    node_lon = np.zeros(N, dtype=np.int32)
    node_lat[np.searchsorted(jrefs, refs[J])] = lat[J]
    node_lon[np.searchsorted(jrefs, refs[J])] = lon[J]
    print(f"Węzły: {N}, odcinki: {E}", flush=True)

    # Długości segmentów (m)
    la = lat.astype(np.float64) / 1e7
    lo = lon.astype(np.float64) / 1e7
    dlat = np.radians(np.diff(la))
    dlon = np.radians(np.diff(lo)) * np.cos(np.radians((la[1:] + la[:-1]) / 2))
    seg = np.sqrt(dlat * dlat + dlon * dlon) * 6371008.8
    cs = np.concatenate([[0.0], np.cumsum(seg)])
    e_len = (cs[eb] - cs[ea]).astype(np.float32)

    # Geometria odcinków (od from do to włącznie)
    glen = (eb - ea + 1)
    g_start = np.concatenate([[0], np.cumsum(glen)]).astype(np.int64)
    G = int(g_start[-1])
    idx = np.repeat(ea - g_start[:-1], glen) + np.arange(G)
    g_lat = lat[idx]
    g_lon = lon[idx]

    ew = way_of[ea]
    e_attr = way_attr[ew]
    e_name = way_name[ew]

    # Lista sąsiedztwa - oba kierunki; dostępność (jednokierunkowość, profil
    # pieszy/rower/samochód) sprawdza aplikacja przy wyznaczaniu trasy.
    fwd_ok = np.ones(E, dtype=bool)
    bwd_ok = np.ones(E, dtype=bool)
    eid = np.arange(E, dtype=np.int64)
    src = np.concatenate([e_from[fwd_ok], e_to[bwd_ok]])
    ent = np.concatenate([eid[fwd_ok] * 2, eid[bwd_ok] * 2 + 1]).astype(np.int32)
    order = np.argsort(src, kind="stable")
    src = src[order]
    ent = ent[order]
    adj_start = np.searchsorted(src, np.arange(N + 1)).astype(np.int32)
    A = len(ent)

    # Siatka przestrzenna
    min_lat = float(la.min()) - CELL_DEG
    min_lon = float(lo.min()) - CELL_DEG
    cols = int(math.ceil((float(lo.max()) - min_lon) / CELL_DEG)) + 2
    rows = int(math.ceil((float(la.max()) - min_lat) / CELL_DEG)) + 2
    # próbkowanie segmentów co ~150 m
    g_la = g_lat.astype(np.float64) / 1e7
    g_lo = g_lon.astype(np.float64) / 1e7
    edge_of_g = np.repeat(np.arange(E, dtype=np.int64), glen)
    s_from = np.arange(G - 1)
    valid = edge_of_g[s_from] == edge_of_g[s_from + 1]
    s_from = s_from[valid]
    d1 = np.radians(g_la[s_from + 1] - g_la[s_from])
    d2 = np.radians(g_lo[s_from + 1] - g_lo[s_from]) * np.cos(np.radians(g_la[s_from]))
    slen = np.sqrt(d1 * d1 + d2 * d2) * 6371008.8
    ns = (np.ceil(slen / 150.0).astype(np.int64)) + 1
    rep = np.repeat(s_from, ns)
    k = np.arange(len(rep)) - np.repeat(np.concatenate([[0], np.cumsum(ns)[:-1]]), ns)
    fr = k / np.repeat(ns - 1, ns).clip(min=1)
    sl = g_la[rep] + (g_la[rep + 1] - g_la[rep]) * fr
    so = g_lo[rep] + (g_lo[rep + 1] - g_lo[rep]) * fr
    cell = ((sl - min_lat) / CELL_DEG).astype(np.int64) * cols + ((so - min_lon) / CELL_DEG).astype(np.int64)
    pair = np.unique(cell * E + edge_of_g[rep])
    p_cell = pair // E
    p_edge = (pair % E).astype(np.int32)
    cell_keys, cell_first = np.unique(p_cell, return_index=True)
    cell_start = np.append(cell_first, len(p_edge)).astype(np.int32)
    C = len(cell_keys)
    GE = len(p_edge)
    print(f"Komórki siatki: {C}, wpisy: {GE}", flush=True)

    # Zakazy skrętu -> (łuk przyjazdu, łuk wyjazdu, rodzaj 0=zakaz 1=nakaz)
    r_rows = set()
    if c.restrictions:
        wayidx = {int(w): i for i, w in enumerate(c.way_id)}
        by_way = {}
        for e_i in range(E):
            by_way.setdefault(int(ew[e_i]), []).append(e_i)
        for val, fw, via, tw in c.restrictions:
            wi, wt = wayidx.get(fw), wayidx.get(tw)
            if wi is None or wt is None:
                continue
            k = int(np.searchsorted(jrefs, via))
            if k >= N or jrefs[k] != via:
                continue
            cf = [e_i for e_i in by_way.get(wi, []) if e_from[e_i] == k or e_to[e_i] == k]
            ct = [e_i for e_i in by_way.get(wt, []) if e_from[e_i] == k or e_to[e_i] == k]
            if len(cf) != 1 or len(ct) != 1:
                continue  # niejednoznaczne (droga nie jest przecięta w węźle via) - pomijamy
            ef, et = cf[0], ct[0]
            arr_dir = 0 if e_to[ef] == k else 1
            leave_dir = 0 if e_from[et] == k else 1
            if val == "no_u_turn" and ef == et:
                leave_dir = 1 - arr_dir
            r_rows.add((ef * 2 + arr_dir, et * 2 + leave_dir, 0 if val.startswith("no_") else 1))
    r_rows = sorted(r_rows)
    R = len(r_rows)
    print(f"Zakazy/nakazy skrętu: {R}", flush=True)

    # Nazwy
    nb = bytearray()
    nstart = [0]
    for s in c.name_list:
        nb += s.encode("utf-8")
        nstart.append(len(nb))
    SB = len(nb)
    while len(nb) % 4:
        nb += b"\0"

    out = os.path.join(outdir, "roads.bin")
    with open(out, "wb") as f:
        hdr = [0x4E415647, 2, N, E, G, A, C, GE, len(c.name_list), SB,
               int(round(min_lat * 1e7)), int(round(min_lon * 1e7)), int(round(CELL_DEG * 1e7)), cols, rows, R]
        f.write(struct.pack("<16i", *hdr))

        def w(arr, dt="<i4"):
            np.asarray(arr).astype(dt).tofile(f)

        w(node_lat); w(node_lon)
        w(e_from); w(e_to); w(g_start); w(e_len, "<f4"); w(e_attr); w(e_name)
        w(g_lat); w(g_lon)
        w(adj_start); w(ent)
        w(cell_keys); w(cell_start); w(p_edge)
        w(np.array(nstart))
        f.write(bytes(nb))
        if R:
            w([r[0] for r in r_rows]); w([r[1] for r in r_rows]); w([r[2] for r in r_rows])
    print(f"Zapisano {out} ({os.path.getsize(out) / 1e6:.1f} MB)", flush=True)

    with open(os.path.join(outdir, "warn.tsv"), "w", encoding="utf-8") as f:
        for kind, la, lo, lim in c.hazards:
            f.write(f"{kind}\t{la:.6f}\t{lo:.6f}\t{lim}\n")
    print(f"Zagrożenia (fotoradary, przejazdy): {len(c.hazards)}", flush=True)
    write_search(c, os.path.join(outdir, "search.tsv"))


def write_search(c, path):
    places = c.places
    rank = {"city": 0, "town": 1, "suburb": 2, "village": 3, "quarter": 4, "neighbourhood": 5, "hamlet": 6}
    # Siatka miejscowości do przypisania ulic (tylko city/town/village/suburb)
    settle = [p for p in places if p[1] in ("city", "town", "village", "suburb")]
    grid = {}
    for i, p in enumerate(settle):
        grid.setdefault((int(p[2] / 0.05), int(p[3] / 0.05)), []).append(i)

    def nearest(la, lo):
        best, bd = None, 1e18
        gi, gj = int(la / 0.05), int(lo / 0.05)
        for r in (0, 1, 2):
            for di in range(-r, r + 1):
                for dj in range(-r, r + 1):
                    if max(abs(di), abs(dj)) != r:
                        continue
                    for i in grid.get((gi + di, gj + dj), ()):
                        p = settle[i]
                        d = (p[2] - la) ** 2 + ((p[3] - lo) * 0.63) ** 2
                        if p[1] == "suburb":
                            d *= 1.5
                        if d < bd:
                            bd, best = d, p
            if best is not None and r >= 1:
                break
        return best

    lines = []
    for nm, typ, la, lo in sorted(places, key=lambda p: rank.get(p[1], 9)):
        ctx = ""
        if typ in ("suburb", "quarter", "neighbourhood", "hamlet"):
            n = nearest(la, lo)
            if n and n[0] != nm:
                ctx = n[0]
        lines.append(f"{nm}\t{PLACE_TYPES[typ]}\t{la:.6f}\t{lo:.6f}\t{ctx}")
    seen = set()
    for nm, la, lo in c.street_points:
        n = nearest(la, lo)
        ctx = n[0] if n else ""
        key = (nm, ctx)
        if key in seen:
            continue
        seen.add(key)
        lines.append(f"{nm}\tulica\t{la:.6f}\t{lo:.6f}\t{ctx}")
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
        f.write("\n")
    print(f"Zapisano {path} ({len(lines)} pozycji)", flush=True)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("pbf")
    ap.add_argument("outdir")
    a = ap.parse_args()
    build(a.pbf, a.outdir)
