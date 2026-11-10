package pl.drogowskaz.nav

import java.util.PriorityQueue

enum class Turn { START, STRAIGHT, SLIGHT_LEFT, LEFT, SHARP_LEFT, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, UTURN, ROUNDABOUT, KEEP_LEFT, KEEP_RIGHT, ARRIVE }

class Maneuver(
    val turn: Turn,
    val lat: Double,
    val lon: Double,
    val atDistance: Double,   // odległość od początku trasy [m]
    val street: String?,
    val roundaboutExit: Int = 0,
) {
    fun text(): String {
        val on = if (street.isNullOrBlank()) "" else " w $street"
        return when (turn) {
            Turn.START -> "Ruszaj" + on
            Turn.STRAIGHT -> "Jedź prosto$on"
            Turn.SLIGHT_LEFT -> "Skręć lekko w lewo$on"
            Turn.LEFT -> "Skręć w lewo$on"
            Turn.SHARP_LEFT -> "Skręć ostro w lewo$on"
            Turn.SLIGHT_RIGHT -> "Skręć lekko w prawo$on"
            Turn.RIGHT -> "Skręć w prawo$on"
            Turn.SHARP_RIGHT -> "Skręć ostro w prawo$on"
            Turn.UTURN -> "Zawróć"
            Turn.ROUNDABOUT -> "Na rondzie zjedź w ${roundaboutExit}. zjazd$on"
            Turn.KEEP_LEFT -> "Trzymaj się lewej$on"
            Turn.KEEP_RIGHT -> "Trzymaj się prawej$on"
            Turn.ARRIVE -> "Cel podróży"
        }
    }
}

class Route(
    val profile: Profile,
    val lats: DoubleArray,
    val lons: DoubleArray,
    val cum: DoubleArray,
    val stepCodes: IntArray,      // edge*2+dir
    val stepGeomStart: IntArray,  // indeks w geometrii, od którego zaczyna się odcinek
    val maneuvers: List<Maneuver>,
    val duration: Double,
) {
    val distance: Double get() = cum.last()
    val edgeSet: Set<Int> by lazy { stepCodes.map { it shr 1 }.toHashSet() }

    /** Punkt na trasie po przejechaniu d metrów: [lat, lon, kurs]. */
    fun pointAt(d: Double): DoubleArray {
        val dd = d.coerceIn(0.0, distance)
        var lo = 0
        var hi = cum.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (cum[mid] <= dd) lo = mid else hi = mid
        }
        val seg = cum[hi] - cum[lo]
        val f = if (seg > 0) (dd - cum[lo]) / seg else 0.0
        return doubleArrayOf(
            lats[lo] + (lats[hi] - lats[lo]) * f,
            lons[lo] + (lons[hi] - lons[lo]) * f,
            Geo.bearing(lats[lo], lons[lo], lats[hi], lons[hi]),
        )
    }

    /** Czas pozostały od danego punktu trasy (proporcjonalnie). */
    fun remainingSeconds(progress: Double): Double =
        if (distance <= 0) 0.0 else duration * (1.0 - (progress / distance).coerceIn(0.0, 1.0))
}

/** Wyznaczanie tras A* po grafie. */
class Router(private val g: RoadGraph) {

    /** Kopiec minimalny na tablicach prymitywnych (priorytet, kod łuku, koszt dojścia). */
    private class MinHeap {
        private var k = DoubleArray(4096)
        private var v = IntArray(4096)
        private var g = DoubleArray(4096)
        var size = 0
            private set
        var topKey = 0.0
        var topVal = 0
        var topG = 0.0

        fun push(key: Double, value: Int, gc: Double) {
            if (size == k.size) { k = k.copyOf(size * 2); v = v.copyOf(size * 2); g = g.copyOf(size * 2) }
            var i = size++
            while (i > 0) {
                val p = (i - 1) ushr 1
                if (k[p] <= key) break
                k[i] = k[p]; v[i] = v[p]; g[i] = g[p]; i = p
            }
            k[i] = key; v[i] = value; g[i] = gc
        }

        fun pop() {
            topKey = k[0]; topVal = v[0]; topG = g[0]
            size--
            if (size > 0) {
                val key = k[size]; val value = v[size]; val gc = g[size]
                var i = 0
                while (true) {
                    var c = 2 * i + 1
                    if (c >= size) break
                    if (c + 1 < size && k[c + 1] < k[c]) c++
                    if (k[c] >= key) break
                    k[i] = k[c]; v[i] = v[c]; g[i] = g[c]; i = c
                }
                k[i] = key; v[i] = value; g[i] = gc
            }
        }
    }

    /** Mapa: kod łuku -> (koszt, poprzednik), adresowanie otwarte; oszczędna pamięciowo. */
    private class ArcMap {
        private var cap = 1 shl 16
        private var keys = IntArray(cap) { -1 }
        private var costs = DoubleArray(cap)
        private var prevs = IntArray(cap)
        private var count = 0

        private fun slot(key: Int): Int {
            val h = key * -1640531527
            var i = (h xor (h ushr 15)) and (cap - 1)
            while (keys[i] != -1 && keys[i] != key) i = (i + 1) and (cap - 1)
            return i
        }

        fun cost(key: Int): Double { val i = slot(key); return if (keys[i] == key) costs[i] else Double.MAX_VALUE }
        fun prev(key: Int): Int = prevs[slot(key)]

        fun put(key: Int, c: Double, p: Int) {
            if (count * 2 >= cap) grow()
            val i = slot(key)
            if (keys[i] != key) { keys[i] = key; count++ }
            costs[i] = c; prevs[i] = p
        }

        private fun grow() {
            val ok = keys; val oc = costs; val op = prevs
            cap *= 2
            keys = IntArray(cap) { -1 }; costs = DoubleArray(cap); prevs = IntArray(cap)
            for (j in ok.indices) if (ok[j] != -1) {
                val i = slot(ok[j]); keys[i] = ok[j]; costs[i] = oc[j]; prevs[i] = op[j]
            }
        }
    }

    /** Odległość wzdłuż geometrii odcinka od jego początku do punktu przyciągniętego. */
    fun offsetOf(s: Snap): Double {
        var d = 0.0
        val gs = g.geomStart(s.edge)
        for (i in gs until s.seg) d += Geo.dist(g.gLat(i), g.gLon(i), g.gLat(i + 1), g.gLon(i + 1))
        d += s.t * Geo.dist(g.gLat(s.seg), g.gLon(s.seg), g.gLat(s.seg + 1), g.gLon(s.seg + 1))
        return d
    }

    /**
     * @param headingKnown true, gdy kierunek jazdy ze Snap jest wiarygodny (samochód nie zawraca).
     */
    fun route(from: Snap, to: Snap, profile: Profile, headingKnown: Boolean): Route? {
        val len1 = g.edgeLen(from.edge)
        val len2 = g.edgeLen(to.edge)
        val o1 = offsetOf(from).coerceIn(0.0, len1)
        val o2 = offsetOf(to).coerceIn(0.0, len2)

        // Ten sam odcinek, cel przed nami
        if (from.edge == to.edge) {
            val d = if (o2 >= o1) 0 else 1
            val dirOk = g.allowed(from.edge, d, profile) && (!profile.isCar || !headingKnown || from.dir == d)
            if (dirOk) return build(profile, from, to, o1, o2, listOf(from.edge * 2 + d))
        }

        val straight = Geo.dist(from.lat, from.lon, to.lat, to.lon)
        val prune = profile.isCar && straight > 40_000.0
        val codes = search(from, to, profile, headingKnown, o1, o2, prune)
            ?: if (prune) search(from, to, profile, headingKnown, o1, o2, false) else null
        return codes?.let { build(profile, from, to, o1, o2, it) }
    }

    /** Czy droga o danej klasie nadaje się do trasy dalekobieżnej (hierarchia dróg). */
    private fun keep(e: Int, m: Int, from: Snap, to: Snap): Boolean {
        if (g.roadClass(e) <= RoadClass.TERTIARY_LINK) return true
        val la = g.nodeLat(m); val lo = g.nodeLon(m)
        return Geo.dist(la, lo, from.lat, from.lon) < 8000.0 || Geo.dist(la, lo, to.lat, to.lon) < 8000.0
    }

    /** Koszt skrętu na skrzyżowaniu (sekundy lub ekwiwalent metrów dla trasy najkrótszej). */
    private fun turnCost(ae: Int, ad: Int, be: Int, bd: Int, deg: Int, p: Profile): Double {
        if (deg <= 2) return 0.0
        val signed = Geo.angleDiff(exitBearing(ae, ad), entryBearing(be, bd)) // dodatni = w prawo
        val ang = kotlin.math.abs(signed)
        return when (p) {
            Profile.CAR_FASTEST -> 2.0 + (if (ang > 150) 20.0 else if (ang > 55) (if (signed < 0) 7.0 else 3.0) else 0.0)
            Profile.CAR_SHORTEST -> if (ang > 55) 10.0 else 0.0
            Profile.BIKE -> 1.0 + (if (ang > 55) 3.0 else 0.0)
            Profile.FOOT -> if (ang > 55) 1.0 else 0.0
        }
    }

    /** A* po łukach (kierunkowych odcinkach) - uwzględnia zakazy skrętu i koszt skrętów. */
    private fun search(
        from: Snap, to: Snap, profile: Profile, headingKnown: Boolean,
        o1: Double, o2: Double, prune: Boolean,
    ): List<Int>? {
        val len1 = g.edgeLen(from.edge)
        val len2 = g.edgeLen(to.edge)
        val h = profile.minCostPerMeter
        val tLat = to.lat; val tLon = to.lon
        fun heur(n: Int) = Geo.dist(g.nodeLat(n), g.nodeLon(n), tLat, tLon) * h

        val map = ArcMap()
        val heap = MinHeap()

        fun seed(d: Int) {
            val remaining = if (d == 0) len1 - o1 else o1
            val code = from.edge * 2 + d
            val c = remaining * profile.costPerMeter(g, from.edge, d)
            if (c < map.cost(code)) { map.put(code, c, -1); heap.push(c + heur(g.target(from.edge, d)), code, c) }
        }
        for (d in 0..1) {
            if (!g.allowed(from.edge, d, profile)) continue
            if (profile.isCar && headingKnown && d != from.dir) continue
            seed(d)
        }
        if (heap.size == 0 && profile.isCar) for (d in 0..1) if (g.allowed(from.edge, d, profile)) seed(d)
        if (heap.size == 0) return null

        // Wjazd na odcinek docelowy z jednego z jego końców
        val entryNode = IntArray(2) { -1 }
        val entryCode = IntArray(2)
        val entryCost = DoubleArray(2)
        for (d in 0..1) {
            if (!g.allowed(to.edge, d, profile)) continue
            entryNode[d] = g.source(to.edge, d)
            entryCode[d] = to.edge * 2 + d
            entryCost[d] = (if (d == 0) o2 else len2 - o2) * profile.costPerMeter(g, to.edge, d)
        }
        if (entryNode[0] < 0 && entryNode[1] < 0) return null

        var best = Double.MAX_VALUE
        var bestArc = -1
        var bestDest = -1
        var expanded = 0

        while (heap.size > 0) {
            heap.pop()
            val f = heap.topKey; val code = heap.topVal; val gc = heap.topG
            if (f >= best) break
            if (gc > map.cost(code)) continue
            if (++expanded > 8_000_000) return null

            val e = code shr 1
            val d = code and 1
            val n = g.target(e, d)
            val s0 = g.adjStart(n)
            val s1 = g.adjEnd(n)
            val deg = s1 - s0

            for (k in 0..1) {
                if (entryNode[k] != n) continue
                val dc = entryCode[k]
                if (profile.isCar && !g.turnAllowed(code, dc)) continue
                val total = gc + turnCost(e, d, dc shr 1, dc and 1, deg, profile) + entryCost[k]
                if (total < best) { best = total; bestArc = code; bestDest = dc }
            }

            for (i in s0 until s1) {
                val b = g.adj(i)
                val be = b shr 1
                val bd = b and 1
                if (be == e && bd != d && deg > 1) continue       // bez zawracania (poza ślepym zaułkiem)
                if (!g.allowed(be, bd, profile)) continue
                if (profile.isCar && !g.turnAllowed(code, b)) continue
                val m = g.target(be, bd)
                if (prune && !keep(be, m, from, to)) continue
                val c = gc + turnCost(e, d, be, bd, deg, profile) + g.edgeLen(be) * profile.costPerMeter(g, be, bd)
                if (c < map.cost(b)) {
                    map.put(b, c, code)
                    heap.push(c + heur(m), b, c)
                }
            }
        }
        if (bestArc < 0) return null

        val out = ArrayList<Int>()
        out.add(bestDest)
        var cur = bestArc
        while (cur >= 0) { out.add(cur); cur = map.prev(cur) }
        out.reverse()
        return out
    }

    // --- budowa geometrii i instrukcji ---

    private fun build(profile: Profile, from: Snap, to: Snap, o1: Double, o2: Double, rawCodes: List<Int>): Route? {
        // Usuń ewentualne duplikaty z początku (start na tym samym odcinku co 1. krok)
        val codes = ArrayList<Int>()
        for (c in rawCodes) if (codes.isEmpty() || codes.last() != c) codes.add(c)
        if (codes.isEmpty()) return null

        val lats = ArrayList<Double>()
        val lons = ArrayList<Double>()
        val stepStart = IntArray(codes.size)
        var durationSec = 0.0

        for ((k, code) in codes.withIndex()) {
            val e = code shr 1
            val d = code and 1
            stepStart[k] = if (lats.isEmpty()) 0 else lats.size - 1   // wspólny punkt złącza
            val gs = g.geomStart(e)
            val ge = g.geomEnd(e)
            val pts = ArrayList<DoubleArray>()
            if (d == 0) for (i in gs until ge) pts.add(doubleArrayOf(g.gLat(i), g.gLon(i)))
            else for (i in ge - 1 downTo gs) pts.add(doubleArrayOf(g.gLat(i), g.gLon(i)))

            var from0 = 0.0
            var to0 = g.edgeLen(e)
            val first = k == 0
            val last = k == codes.size - 1
            if (first && e == from.edge) from0 = if (d == 0) o1 else g.edgeLen(e) - o1
            if (last && e == to.edge) to0 = if (d == 0) o2 else g.edgeLen(e) - o2
            durationSec += (to0 - from0).coerceAtLeast(0.0) / profile.speedMs(g, e, d)
            clipAppend(pts, from0, to0, lats, lons)
        }
        if (lats.size < 2) {
            lats.add(lats.firstOrNull() ?: to.lat); lons.add(lons.firstOrNull() ?: to.lon)
        }
        val n = lats.size
        val cum = DoubleArray(n)
        for (i in 1 until n) cum[i] = cum[i - 1] + Geo.dist(lats[i - 1], lons[i - 1], lats[i], lons[i])
        val stepCodes = codes.toIntArray()
        val mans = buildManeuvers(profile, stepCodes, stepStart, lats, lons, cum)
        return Route(profile, lats.toDoubleArray(), lons.toDoubleArray(), cum, stepCodes, stepStart, mans, durationSec)
    }

    /** Dodaje punkty odcinka od odległości from0 do to0 (wzdłuż geometrii). */
    private fun clipAppend(
        pts: List<DoubleArray>, from0: Double, to0: Double,
        lats: ArrayList<Double>, lons: ArrayList<Double>,
    ) {
        fun add(la: Double, lo: Double) {
            if (lats.isNotEmpty() && lats.last() == la && lons.last() == lo) return
            lats.add(la); lons.add(lo)
        }
        fun lerp(a: DoubleArray, b: DoubleArray, f: Double) =
            add(a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f)

        var acc = 0.0
        var started = false
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            val seg = Geo.dist(a[0], a[1], b[0], b[1])
            val a0 = acc
            val a1 = acc + seg
            acc = a1
            if (a1 < from0) continue
            if (!started) {
                lerp(a, b, if (seg > 0) ((from0 - a0) / seg).coerceIn(0.0, 1.0) else 0.0)
                started = true
            }
            if (to0 <= a1) {
                lerp(a, b, if (seg > 0) ((to0 - a0) / seg).coerceIn(0.0, 1.0) else 0.0)
                return
            }
            add(b[0], b[1])
        }
        if (!started) add(pts.last()[0], pts.last()[1])
    }

    private fun buildManeuvers(
        profile: Profile, codes: IntArray, stepStart: IntArray,
        lats: List<Double>, lons: List<Double>, cum: DoubleArray,
    ): List<Maneuver> {
        val out = ArrayList<Maneuver>()
        out.add(Maneuver(Turn.START, lats[0], lons[0], 0.0, g.name(codes[0] shr 1)))
        var k = 0
        while (k < codes.size - 1) {
            val e1 = codes[k] shr 1; val d1 = codes[k] and 1
            val e2 = codes[k + 1] shr 1; val d2 = codes[k + 1] and 1
            val node = g.target(e1, d1)
            val gi = stepStart[k + 1].coerceAtMost(lats.size - 1)
            val atDist = cum[gi]

            if (g.isRoundabout(e2) && !g.isRoundabout(e1)) {
                var exits = 0
                var j = k + 1
                while (j < codes.size && g.isRoundabout(codes[j] shr 1)) {
                    val ej = codes[j] shr 1
                    val dj = codes[j] and 1
                    val nextIsRound = j + 1 < codes.size && g.isRoundabout(codes[j + 1] shr 1)
                    if (!nextIsRound) exits++
                    else if (countExits(g.target(ej, dj), ej, dj, profile) > 0) exits++
                    j++
                }
                val nm = if (j < codes.size) g.name(codes[j] shr 1) else null
                out.add(Maneuver(Turn.ROUNDABOUT, g.nodeLat(node), g.nodeLon(node), atDist, nm, exits.coerceAtLeast(1)))
                k = j   // złącze wyjazdu z ronda jest już obsłużone
                continue
            }
            if (g.isRoundabout(e1) && g.isRoundabout(e2)) { k++; continue }

            val inB = exitBearing(e1, d1)
            val outB = entryBearing(e2, d2)
            val ang = Geo.angleDiff(inB, outB)
            val alt = countExits(node, e1, d1, profile, excludeEdge = e2)
            val abs = kotlin.math.abs(ang)
            val turn: Turn? = when {
                abs > 160 -> Turn.UTURN
                abs >= 120 -> if (ang > 0) Turn.SHARP_RIGHT else Turn.SHARP_LEFT
                abs >= 55 -> if (ang > 0) Turn.RIGHT else Turn.LEFT
                abs >= 28 -> if (alt > 0 || abs >= 40) (if (ang > 0) Turn.SLIGHT_RIGHT else Turn.SLIGHT_LEFT) else null
                abs >= 12 && alt >= 1 -> if (ang > 0) Turn.KEEP_RIGHT else Turn.KEEP_LEFT
                else -> null
            }
            if (turn != null) {
                out.add(Maneuver(turn, g.nodeLat(node), g.nodeLon(node), atDist, g.name(e2)))
            }
            k++
        }
        out.add(Maneuver(Turn.ARRIVE, lats.last(), lons.last(), cum.last(), null))
        return out
    }

    /** Ile innych możliwości wyjazdu z węzła (poza wjazdem i ewentualnie wskazanym odcinkiem). */
    private fun countExits(node: Int, inEdge: Int, inDir: Int, profile: Profile, excludeEdge: Int = -1): Int {
        var c = 0
        for (i in g.adjStart(node) until g.adjEnd(node)) {
            val code = g.adj(i)
            val e = code shr 1
            val d = code and 1
            if (e == inEdge || e == excludeEdge) continue
            if (g.isRoundabout(e) && g.isRoundabout(inEdge)) continue
            if (g.allowed(e, d, profile)) c++
        }
        return c
    }

    private fun exitBearing(e: Int, d: Int): Double {
        val gs = g.geomStart(e); val ge = g.geomEnd(e)
        return if (d == 0) Geo.bearing(g.gLat(ge - 2), g.gLon(ge - 2), g.gLat(ge - 1), g.gLon(ge - 1))
        else Geo.bearing(g.gLat(gs + 1), g.gLon(gs + 1), g.gLat(gs), g.gLon(gs))
    }

    private fun entryBearing(e: Int, d: Int): Double {
        val gs = g.geomStart(e); val ge = g.geomEnd(e)
        return if (d == 0) Geo.bearing(g.gLat(gs), g.gLon(gs), g.gLat(gs + 1), g.gLon(gs + 1))
        else Geo.bearing(g.gLat(ge - 1), g.gLon(ge - 1), g.gLat(ge - 2), g.gLon(ge - 2))
    }
}
