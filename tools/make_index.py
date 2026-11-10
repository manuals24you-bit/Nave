#!/usr/bin/env python3
"""Tworzy/aktualizuje index.json (lista paczek) na podstawie plików w katalogu.

Pliki: <id>-map.pmtiles, <id>-roads.bin, <id>-search.tsv
Istniejący index.json jest scalany - nowe paczki nadpisują stare o tym samym id.
"""
import datetime
import json
import os
import sys

NAMES = {
    "dolnoslaskie": "Dolnośląskie", "kujawsko-pomorskie": "Kujawsko-pomorskie", "lodzkie": "Łódzkie",
    "lubelskie": "Lubelskie", "lubuskie": "Lubuskie", "malopolskie": "Małopolskie",
    "mazowieckie": "Mazowieckie", "opolskie": "Opolskie", "podkarpackie": "Podkarpackie",
    "podlaskie": "Podlaskie", "pomorskie": "Pomorskie", "slaskie": "Śląskie",
    "swietokrzyskie": "Świętokrzyskie", "warminsko-mazurskie": "Warmińsko-mazurskie",
    "wielkopolskie": "Wielkopolskie", "zachodniopomorskie": "Zachodniopomorskie",
    "polska": "Polska – tylko trasy (podróże między województwami)",
}
SUFFIXES = ["map.pmtiles", "roads.bin", "search.tsv", "warn.tsv"]


def main(d):
    path = os.path.join(d, "index.json")
    packs = {}
    if os.path.exists(path):
        try:
            for p in json.load(open(path, encoding="utf-8"))["packs"]:
                packs[p["id"]] = p
        except Exception:
            pass
    today = datetime.date.today().isoformat()
    ids = set()
    for f in os.listdir(d):
        for s in SUFFIXES:
            if f.endswith("-" + s):
                ids.add(f[: -len(s) - 1])
    for i in sorted(ids):
        files = {}
        for s in SUFFIXES:
            fp = os.path.join(d, f"{i}-{s}")
            if os.path.exists(fp):
                files[s] = os.path.getsize(fp)
        if "roads.bin" not in files:
            continue
        kind = "region" if "map.pmtiles" in files else "routing"
        packs[i] = {"id": i, "name": NAMES.get(i, i), "kind": kind, "files": files, "date": today}
    out = {"packs": sorted(packs.values(), key=lambda p: (p["kind"] != "region", p["name"]))}
    json.dump(out, open(path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print(json.dumps(out, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main(sys.argv[1])
