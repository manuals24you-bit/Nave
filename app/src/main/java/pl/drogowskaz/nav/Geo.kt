package pl.drogowskaz.nav

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    private const val R = 6371008.8

    fun dist(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1) * cos(Math.toRadians((lat1 + lat2) / 2))
        return sqrt(dLat * dLat + dLon * dLon) * R
    }

    /** Kierunek 0..360 (0 = północ, zgodnie z ruchem wskazówek zegara). */
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Różnica kątów w zakresie (-180, 180]. Dodatnia = w prawo. */
    fun angleDiff(from: Double, to: Double): Double {
        var d = (to - from) % 360.0
        if (d > 180) d -= 360
        if (d <= -180) d += 360
        return d
    }

    class Projection {
        var t = 0.0
        var dist = 0.0
        var lat = 0.0
        var lon = 0.0
    }

    /** Rzut punktu P na odcinek AB (lokalne płaskie przybliżenie). */
    fun project(
        pLat: Double, pLon: Double,
        aLat: Double, aLon: Double, bLat: Double, bLon: Double,
        out: Projection
    ) {
        val k = cos(Math.toRadians(pLat)) * 111320.0
        val ax = (aLon - pLon) * k
        val ay = (aLat - pLat) * 110540.0
        val bx = (bLon - pLon) * k
        val by = (bLat - pLat) * 110540.0
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        var t = if (len2 > 1e-9) -(ax * dx + ay * dy) / len2 else 0.0
        t = t.coerceIn(0.0, 1.0)
        val x = ax + t * dx
        val y = ay + t * dy
        out.t = t
        out.dist = sqrt(x * x + y * y)
        out.lat = aLat + (bLat - aLat) * t
        out.lon = aLon + (bLon - aLon) * t
    }

    fun lerpAngle(a: Double, b: Double, f: Double): Double = (a + angleDiff(a, b) * f + 360.0) % 360.0
}
