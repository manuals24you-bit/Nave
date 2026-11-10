package pl.drogowskaz.nav

import kotlin.math.abs
import kotlin.math.min

/** Pozycja przyciągnięta do drogi. */
data class Snap(
    val edge: Int,
    val dir: Int,          // kierunek jazdy po odcinku (0 = from->to)
    val seg: Int,          // indeks punktu geometrii początku segmentu (globalny)
    val t: Double,         // położenie na segmencie 0..1
    val lat: Double,
    val lon: Double,
    val distance: Double,  // odległość surowej pozycji GPS od drogi [m]
    val bearing: Double,   // kierunek drogi zgodny z kierunkiem jazdy
)

/**
 * Dopasowanie pozycji GPS do sieci dróg.
 * Bierze pod uwagę: odległość od drogi, zgodność kierunku jazdy z kierunkiem drogi,
 * ciągłość (ten sam / połączony odcinek co poprzednio), jednokierunkowość,
 * trasę nawigacji oraz klasę drogi (niechętnie wybiera drogi serwisowe/parkingi).
 */
class MapMatcher(private val g: RoadGraph) {
    var last: Snap? = null
        private set
    private var lastTime = 0L

    /** Odcinki aktualnej trasy - preferowane podczas nawigacji. */
    var routeEdges: Set<Int> = emptySet()

    /** Rodzaj poruszania się - decyduje, które drogi bierzemy pod uwagę. */
    var profile: Profile = Profile.CAR_FASTEST

    private val cand = HashSet<Int>()
    private val proj = Geo.Projection()

    fun reset() { last = null }

    /**
     * @param bearing kurs GPS lub null gdy niewiarygodny
     * @param speed prędkość [m/s]
     */
    fun match(lat: Double, lon: Double, accuracy: Float, speed: Float, bearing: Float?, nowMs: Long): Snap? {
        val acc = accuracy.toDouble().coerceIn(3.0, 60.0)
        val radius = (acc * 2.0).coerceIn(20.0, 60.0)
        val sigma = (acc * 0.8).coerceIn(6.0, 25.0)
        val moving = speed > 2.5f && bearing != null
        val prev = if (nowMs - lastTime < 15000) last else null

        cand.clear()
        g.edgesNear(lat, lon, radius, cand)

        var best: Snap? = null
        var bestCost = Double.MAX_VALUE

        for (e in cand) {
            if (!g.usable(e, profile)) continue
            // Najbliższy segment odcinka
            val gs = g.geomStart(e)
            val ge = g.geomEnd(e)
            var segBest = -1
            var dBest = Double.MAX_VALUE
            var tBest = 0.0
            var pLat = 0.0
            var pLon = 0.0
            for (i in gs until ge - 1) {
                Geo.project(lat, lon, g.gLat(i), g.gLon(i), g.gLat(i + 1), g.gLon(i + 1), proj)
                if (proj.dist < dBest) {
                    dBest = proj.dist; segBest = i; tBest = proj.t; pLat = proj.lat; pLon = proj.lon
                }
            }
            if (segBest < 0 || dBest > radius) continue

            val segBearing = Geo.bearing(g.gLat(segBest), g.gLon(segBest), g.gLat(segBest + 1), g.gLon(segBest + 1))
            val fwdOk = g.allowed(e, 0, profile)
            val bwdOk = g.allowed(e, 1, profile)

            var dir: Int
            var headingCost = 0.0
            if (moving) {
                val d0 = abs(Geo.angleDiff(bearing!!.toDouble(), segBearing))
                val d1 = 180.0 - d0
                dir = if (d0 <= d1) 0 else 1
                var diff = min(d0, d1)
                if (!(if (dir == 0) fwdOk else bwdOk)) {
                    // jazda pod prąd jednokierunkowej - mało prawdopodobne
                    diff = if (dir == 0) d1 else d0
                    dir = 1 - dir
                    headingCost += 4.0
                }
                headingCost += min((diff / 35.0) * (diff / 35.0), 6.0)
            } else {
                dir = if (prev != null && prev.edge == e) prev.dir else if (fwdOk) 0 else 1
            }

            var cost = (dBest / sigma) * (dBest / sigma) + headingCost
            if (prev != null) {
                if (prev.edge == e) cost -= 1.2
                else if (connected(prev.edge, e)) cost -= 0.6
            }
            if (routeEdges.contains(e)) cost -= 1.5
            if (profile.isCar) when (g.roadClass(e)) {
                RoadClass.SERVICE, RoadClass.TRACK -> cost += 0.8
                RoadClass.LIVING_STREET -> cost += 0.4
            }

            if (cost < bestCost) {
                bestCost = cost
                val b = if (dir == 0) segBearing else (segBearing + 180.0) % 360.0
                best = Snap(e, dir, segBest, tBest, pLat, pLon, dBest, b)
            }
        }

        if (best != null) {
            last = best
            lastTime = nowMs
        }
        return best
    }

    private fun connected(a: Int, b: Int): Boolean {
        val af = g.edgeFrom(a); val at = g.edgeTo(a)
        val bf = g.edgeFrom(b); val bt = g.edgeTo(b)
        return af == bf || af == bt || at == bf || at == bt
    }

    /** Jednorazowe przyciągnięcie punktu (np. cel wybrany na mapie) - bez kierunku. */
    fun snapPoint(lat: Double, lon: Double, p: Profile, maxDist: Double = 500.0): Snap? {
        var radius = 50.0
        while (radius <= maxDist) {
            cand.clear()
            g.edgesNear(lat, lon, radius, cand)
            var best: Snap? = null
            var bestCost = Double.MAX_VALUE
            for (e in cand) {
                if (!g.usable(e, p)) continue
                for (i in g.geomStart(e) until g.geomEnd(e) - 1) {
                    Geo.project(lat, lon, g.gLat(i), g.gLon(i), g.gLat(i + 1), g.gLon(i + 1), proj)
                    // lekka preferencja zwykłych ulic nad serwisowymi
                    val c = proj.dist + if (p.isCar && g.roadClass(e) == RoadClass.SERVICE) 15.0 else 0.0
                    if (c < bestCost && proj.dist <= radius) {
                        bestCost = c
                        val b = Geo.bearing(g.gLat(i), g.gLon(i), g.gLat(i + 1), g.gLon(i + 1))
                        best = Snap(e, if (g.allowed(e, 0, p)) 0 else 1, i, proj.t, proj.lat, proj.lon, proj.dist, b)
                    }
                }
            }
            if (best != null) return best
            radius *= 2.5
        }
        return null
    }
}
