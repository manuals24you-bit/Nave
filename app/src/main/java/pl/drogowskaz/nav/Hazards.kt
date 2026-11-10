package pl.drogowskaz.nav

import java.io.File
import kotlin.math.cos
import kotlin.math.floor

/** Ostrzeżenie: fotoradar / przejazd kolejowy z OSM albo zgłoszenie użytkownika. */
class Hazard(val kind: String, val lat: Double, val lon: Double, val limit: Int = 0, val createdMs: Long = 0L) {
    val user: Boolean get() = createdMs > 0L
    val key: String get() = "$kind:${(lat * 1e5).toInt()}:${(lon * 1e5).toInt()}"

    fun label(): String = when (kind) {
        "camera" -> "Fotoradar" + if (limit > 0) " ($limit)" else ""
        "crossing" -> "Przejazd kolejowy"
        "police" -> "Policja"
        "accident" -> "Wypadek"
        "obstacle" -> "Przeszkoda na drodze"
        "traffic" -> "Korek"
        "mobile_camera" -> "Mobilny fotoradar"
        else -> "Uwaga"
    }

    fun icon(): String = when (kind) {
        "camera", "mobile_camera" -> "📷"
        "crossing" -> "🚆"
        "police" -> "👮"
        "accident" -> "💥"
        "obstacle" -> "⚠️"
        "traffic" -> "🚗"
        else -> "⚠️"
    }

    companion object {
        /** Jak długo zgłoszenie użytkownika jest aktualne [ms]. */
        fun ttl(kind: String): Long = when (kind) {
            "traffic" -> 60L * 60_000
            "obstacle" -> 6L * 3_600_000
            else -> 3L * 3_600_000
        }

        val REPORT_KINDS = listOf("police" to "👮 Policja", "accident" to "💥 Wypadek", "obstacle" to "⚠️ Przeszkoda na drodze",
            "traffic" to "🚗 Korek", "mobile_camera" to "📷 Mobilny fotoradar")
    }
}

/** Indeks przestrzenny ostrzeżeń (siatka ~1 km). */
class HazardIndex {
    private val cell = 0.01
    private var grid = HashMap<Long, ArrayList<Hazard>>()
    private var userList: List<Hazard> = emptyList()

    private fun key(la: Double, lo: Double): Long =
        (floor(la / cell).toLong() shl 32) xor (floor(lo / cell).toLong() and 0xffffffffL)

    /** Wczytuje pliki warn.tsv: rodzaj \t lat \t lon \t limit */
    fun load(files: List<File>) {
        val g = HashMap<Long, ArrayList<Hazard>>()
        for (f in files) {
            if (!f.exists()) continue
            f.forEachLine { line ->
                val p = line.split('\t')
                if (p.size >= 3) {
                    val la = p[1].toDoubleOrNull(); val lo = p[2].toDoubleOrNull()
                    if (la != null && lo != null) {
                        g.getOrPut(key(la, lo)) { ArrayList() }.add(Hazard(p[0], la, lo, p.getOrNull(3)?.toIntOrNull() ?: 0))
                    }
                }
            }
        }
        synchronized(this) { grid = g }
    }

    @Synchronized
    fun setUser(list: List<Hazard>) { userList = list }

    @Synchronized
    fun users(): List<Hazard> = userList

    @Synchronized
    fun near(lat: Double, lon: Double, radiusM: Double): List<Hazard> {
        val out = ArrayList<Hazard>()
        val dLat = radiusM / 110540.0
        val dLon = radiusM / (111320.0 * cos(Math.toRadians(lat)))
        val r0 = floor((lat - dLat) / cell).toLong(); val r1 = floor((lat + dLat) / cell).toLong()
        val c0 = floor((lon - dLon) / cell).toLong(); val c1 = floor((lon + dLon) / cell).toLong()
        for (r in r0..r1) for (c in c0..c1) {
            grid[(r shl 32) xor (c and 0xffffffffL)]?.let { out.addAll(it) }
        }
        for (u in userList) if (Geo.dist(lat, lon, u.lat, u.lon) <= radiusM * 1.5) out.add(u)
        return out
    }
}
