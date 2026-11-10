package pl.drogowskaz.nav

import android.location.Location
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Stan prezentowany w interfejsie po każdej aktualizacji pozycji. */
class Guidance(
    val lat: Double,
    val lon: Double,
    val bearing: Double,
    val speedKmh: Int,          // prędkość z GPS
    val limitKmh: Int,          // dozwolona prędkość (0 = brak danych)
    val street: String?,
    val snapped: Boolean,
    val gpsLost: Boolean,
    val next: Maneuver?,
    val nextDistance: Double,
    val thenManeuver: Maneuver?,
    val remainingMeters: Double,
    val remainingSeconds: Double,
    val offRoute: Boolean,
    val hazard: Hazard? = null,
    val hazardDistance: Double = 0.0,
)

interface NavListener {
    fun onGuidance(g: Guidance)
    fun onRouteChanged(route: Route?)
    fun onSpeak(text: String)
    fun onArrived()
    fun onStatus(msg: String)
}

/**
 * Silnik nawigacji: dopasowanie pozycji do drogi, prowadzenie po trasie,
 * ponowne wyznaczanie, komunikaty głosowe i liczenie pozycji w tunelach (dead reckoning).
 */
class Navigator(private val l: NavListener) {
    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()

    var graph: RoadGraph? = null
        set(v) { field = v; matcher = v?.let { MapMatcher(it) }; router = v?.let { Router(it) } }
    private var matcher: MapMatcher? = null
    private var router: Router? = null

    var hazards: HazardIndex? = null
    private val warned = HashSet<String>()

    var profile: Profile = Profile.CAR_FASTEST
    var route: Route? = null
        private set
    private var destination: Snap? = null
    private var destLat = 0.0
    private var destLon = 0.0

    private var progress = 0.0
    private var geomIdx = 0
    private var maneuverIdx = 1
    private var farSpoken = -1
    private var nearSpoken = -1
    private var offCount = 0
    private var rerouting = false
    private var lastRerouteMs = 0L

    // ostatni znany stan
    private var lastFixMs = 0L
    private var lastSpeedMs = 0.0
    private var lastBearing = 0.0
    private var lastLat = 0.0
    private var lastLon = 0.0
    private var lastTickMs = 0L
    var lastGuidance: Guidance? = null
        private set

    val navigating: Boolean get() = route != null && destination != null

    fun snapToRoad(lat: Double, lon: Double): Snap? = matcher?.snapPoint(lat, lon, profile)

    // ---------- wyznaczanie trasy ----------

    /** Wylicza trasę (bez rozpoczynania prowadzenia) - do podglądu i porównania profili. */
    fun computeRoute(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, p: Profile, done: (Route?, String?) -> Unit) {
        val gr = graph; val r = router
        if (gr == null || r == null) { done(null, "Brak pobranych danych tras dla tego regionu."); return }
        val headingKnown = p.isCar && lastSpeedMs > 3.0 && lastFixMs > 0 && Geo.dist(fromLat, fromLon, lastLat, lastLon) < 40
        val lastSnap = matcher?.last
        exec.execute {
            val snapper = MapMatcher(gr)   // osobna instancja - bezpieczna dla wątku roboczego
            val from = if (headingKnown && lastSnap != null && Geo.dist(lastSnap.lat, lastSnap.lon, fromLat, fromLon) < 40) lastSnap
                else snapper.snapPoint(fromLat, fromLon, p, 600.0)
            val to = snapper.snapPoint(toLat, toLon, p, 800.0)
            val res: Route?
            val err: String?
            if (from == null) { res = null; err = "Twoja pozycja jest poza pobraną mapą." }
            else if (to == null) { res = null; err = "Cel jest poza pobraną mapą - pobierz kolejny region." }
            else {
                res = r.route(from, to, p, headingKnown)
                err = if (res == null) "Nie znaleziono trasy (${p.label.lowercase()}). Sprawdź, czy cel jest dostępny dla tego rodzaju trasy." else null
            }
            main.post { done(res, err) }
        }
    }

    fun start(r: Route, toLat: Double, toLon: Double) {
        route = r
        destLat = toLat; destLon = toLon
        profile = r.profile
        destination = matcher?.snapPoint(toLat, toLon, profile, 800.0)
        resetProgress()
        matcher?.routeEdges = r.edgeSet
        l.onRouteChanged(r)
        l.onSpeak("Rozpoczynam nawigację. " + (r.maneuvers.getOrNull(1)?.let { describeAhead(it, r.maneuvers[1].atDistance) } ?: ""))
    }

    fun stop() {
        route = null; destination = null
        matcher?.routeEdges = emptySet()
        l.onRouteChanged(null)
    }

    private fun resetProgress() {
        progress = 0.0; geomIdx = 0; maneuverIdx = 1; farSpoken = -1; nearSpoken = -1; offCount = 0
    }

    // ---------- aktualizacje GPS ----------

    fun onLocation(loc: Location) {
        val now = System.currentTimeMillis()
        val speedMs = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0
        val acc = if (loc.hasAccuracy()) loc.accuracy else 20f
        val bearingOk = loc.hasBearing() && speedMs > 2.0
        lastFixMs = now
        lastSpeedMs = speedMs

        var lat = loc.latitude
        var lon = loc.longitude
        var bearing = if (bearingOk) loc.bearing.toDouble() else lastBearing
        var limit = 0
        var street: String? = null
        var snapped = false
        var snap: Snap? = null

        val m = matcher
        val g = graph
        if (m != null && g != null && g.covers(lat, lon) && acc <= 120f) {
            snap = m.match(lat, lon, acc, speedMs.toFloat(), if (bearingOk) loc.bearing else null, now)
            if (snap != null && snap.distance <= max(40.0, acc * 1.8)) {
                snapped = true
                lat = snap.lat; lon = snap.lon
                if (speedMs > 1.5 || lastGuidance == null) bearing = Geo.lerpAngle(lastBearing, snap.bearing, 0.6)
                limit = g.maxSpeed(snap.edge, snap.dir)
                street = g.name(snap.edge)
            }
        }
        lastBearing = bearing
        lastLat = lat; lastLon = lon
        lastTickMs = now

        var offRoute = false
        val r = route
        if (r != null && destination != null) {
            offRoute = updateProgress(r, lat, lon, snap, acc)
            if (offRoute) offCount++ else offCount = 0
            if (offCount >= 3 && !rerouting && now - lastRerouteMs > 4000) reroute(snap, lat, lon)
        }
        publish(lat, lon, bearing, speedMs, limit, street, snapped, false, offRoute)
    }

    /** Wywoływane co ~500 ms: liczenie pozycji gdy GPS zniknął (tunel). */
    fun tick() {
        val r = route ?: return
        if (destination == null || lastFixMs == 0L) return
        val now = System.currentTimeMillis()
        val dt = (now - lastTickMs) / 1000.0
        lastTickMs = now
        if (now - lastFixMs > 2500 && lastSpeedMs > 2.0 && now - lastFixMs < 180000) {
            progress = min(r.distance, progress + lastSpeedMs * dt)
            val p = r.pointAt(progress)
            lastLat = p[0]; lastLon = p[1]; lastBearing = p[2]
            val g = graph
            var limit = 0
            var street: String? = null
            if (g != null) {
                val code = r.stepCodes.getOrNull(stepAt(r, progress))
                if (code != null) { limit = g.maxSpeed(code shr 1, code and 1); street = g.name(code shr 1) }
            }
            publish(p[0], p[1], p[2], lastSpeedMs, limit, street, true, true, false)
        }
    }

    private fun stepAt(r: Route, d: Double): Int {
        var k = 0
        for (i in r.stepGeomStart.indices) {
            if (r.cum[r.stepGeomStart[i].coerceAtMost(r.cum.size - 1)] <= d) k = i else break
        }
        return k
    }

    /** Aktualizuje postęp na trasie. Zwraca true, gdy pozycja jest poza trasą. */
    private fun updateProgress(r: Route, lat: Double, lon: Double, snap: Snap?, acc: Float): Boolean {
        val proj = Geo.Projection()
        var best = Double.MAX_VALUE
        var bestIdx = geomIdx
        var bestT = 0.0
        val n = r.lats.size
        fun scan(from: Int, to: Int) {
            for (i in from until to) {
                if (i + 1 >= n) break
                Geo.project(lat, lon, r.lats[i], r.lons[i], r.lats[i + 1], r.lons[i + 1], proj)
                if (proj.dist < best) { best = proj.dist; bestIdx = i; bestT = proj.t }
            }
        }
        scan(max(0, geomIdx - 2), min(n - 1, geomIdx + 120))
        val tol = max(35.0, acc * 1.6)
        if (best > tol) scan(0, n - 1) // pełne przeszukanie - mogliśmy ominąć fragment
        if (best <= tol * 1.5) {
            val seg = r.cum[min(bestIdx + 1, n - 1)] - r.cum[bestIdx]
            val p = r.cum[bestIdx] + bestT * seg
            if (p >= progress - 30) { progress = max(progress, p); geomIdx = bestIdx }
            // zbyt długo na odcinku spoza trasy?
            if (snap != null && !r.edgeSet.contains(snap.edge) && snap.distance < 25 && best > 20) return true
            return false
        }
        return true
    }

    private fun reroute(snap: Snap?, lat: Double, lon: Double) {
        val r = route ?: return
        rerouting = true
        lastRerouteMs = System.currentTimeMillis()
        l.onStatus("Przeliczanie trasy…")
        l.onSpeak("Przeliczam trasę")
        computeRoute(lat, lon, destLat, destLon, r.profile) { nr, err ->
            rerouting = false
            if (nr != null && route != null) {
                route = nr
                resetProgress()
                matcher?.routeEdges = nr.edgeSet
                l.onRouteChanged(nr)
            } else if (err != null) l.onStatus(err)
        }
    }

    // ---------- komunikaty ----------

    private fun publish(lat: Double, lon: Double, bearing: Double, speedMs: Double, limit: Int, street: String?,
                        snapped: Boolean, lost: Boolean, off: Boolean) {
        val r = route
        var next: Maneuver? = null
        var then: Maneuver? = null
        var nextDist = 0.0
        var remM = 0.0
        var remS = 0.0
        if (r != null && destination != null) {
            while (maneuverIdx < r.maneuvers.size - 1 && r.maneuvers[maneuverIdx].atDistance < progress + 8) {
                maneuverIdx++; farSpoken = -1; nearSpoken = -1
            }
            next = r.maneuvers.getOrNull(maneuverIdx)
            then = r.maneuvers.getOrNull(maneuverIdx + 1)
            nextDist = (next?.atDistance ?: r.distance) - progress
            remM = max(0.0, r.distance - progress)
            remS = r.remainingSeconds(progress)
            if (remM < 25.0 && !off) {
                val arrived = lastGuidance?.let { true } ?: true
                if (arrived) {
                    l.onSpeak("Dojechałeś do celu")
                    l.onArrived()
                    stop()
                    next = null
                }
            } else if (next != null) speakIfNeeded(next, nextDist, speedMs)
        }
        var hz: Hazard? = null
        var hzDist = 0.0
        if (speedMs > 1.5 && profile != Profile.FOOT) {
            scanHazards(lat, lon, bearing, speedMs)?.let { hz = it.first; hzDist = it.second }
        }
        val g = Guidance(lat, lon, bearing, (speedMs * 3.6).toInt(), limit, street, snapped, lost,
            next, nextDist, then, remM, remS, off, hz, hzDist)
        lastGuidance = g
        l.onGuidance(g)
    }

    /** Najbliższe ostrzeżenie przed nami (na trasie lub na kursie jazdy). */
    private fun scanHazards(lat: Double, lon: Double, bearing: Double, speedMs: Double): Pair<Hazard, Double>? {
        val hi = hazards ?: return null
        val radius = (speedMs * 25).coerceIn(250.0, 900.0)
        var best: Hazard? = null
        var bestD = Double.MAX_VALUE
        for (h in hi.near(lat, lon, radius)) {
            val d = Geo.dist(lat, lon, h.lat, h.lon)
            if (d < 15.0 || d > radius) continue
            val diff = abs(Geo.angleDiff(bearing, Geo.bearing(lat, lon, h.lat, h.lon)))
            if (diff > 30.0) continue
            if (d * Math.sin(Math.toRadians(diff)) > 22.0) continue
            if (route != null && !nearRoute(h)) continue
            if (d < bestD) { bestD = d; best = h }
        }
        val h = best ?: return null
        val speakAt = (speedMs * 12).coerceIn(150.0, 450.0)
        if (bestD <= speakAt && warned.add(h.key)) {
            if (warned.size > 300) { warned.clear(); warned.add(h.key) }
            val m = (bestD / 50).toInt() * 50
            l.onSpeak("Uwaga, ${h.label().substringBefore(" (").replaceFirstChar { it.lowercase() }} za ${if (m >= 100) m else 50} metrów")
        }
        return h to bestD
    }

    private fun nearRoute(h: Hazard): Boolean {
        val r = route ?: return true
        val proj = Geo.Projection()
        val from = max(0, geomIdx - 1)
        val to = min(r.lats.size - 1, geomIdx + 150)
        for (i in from until to) {
            Geo.project(h.lat, h.lon, r.lats[i], r.lons[i], r.lats[i + 1], r.lons[i + 1], proj)
            if (proj.dist < 25.0) return true
        }
        return false
    }

    private fun speakIfNeeded(m: Maneuver, dist: Double, speedMs: Double) {
        if (m.turn == Turn.START) return
        val far = (speedMs * 25).coerceIn(if (profile.isCar) 200.0 else 80.0, 1500.0)
        val near = (speedMs * 7).coerceIn(if (profile.isCar) 40.0 else 20.0, 250.0)
        if (dist <= near && nearSpoken != maneuverIdx) {
            nearSpoken = maneuverIdx; farSpoken = maneuverIdx
            l.onSpeak(m.text())
        } else if (dist <= far && dist > near * 1.6 && farSpoken != maneuverIdx) {
            farSpoken = maneuverIdx
            l.onSpeak(describeAhead(m, dist))
        }
    }

    private fun describeAhead(m: Maneuver, dist: Double): String {
        val d = when {
            dist >= 1000 -> String.format(java.util.Locale("pl"), "%.1f kilometra", dist / 1000).replace('.', ',')
            dist >= 100 -> "${((dist / 50).toInt() * 50)} metrów"
            else -> "${max(10, (dist / 10).toInt() * 10)} metrów"
        }
        return "Za $d, ${m.text().replaceFirstChar { it.lowercase() }}"
    }

    @Suppress("unused")
    private fun absD(x: Double) = abs(x)
}
