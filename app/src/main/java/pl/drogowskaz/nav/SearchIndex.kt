package pl.drogowskaz.nav

import java.io.File
import java.text.Normalizer

class PlaceHit(val name: String, val kind: String, val lat: Double, val lon: Double, val context: String) {
    val title: String get() = if (context.isNotEmpty() && context != name) "$name, $context" else name
}

/** Wyszukiwarka miejscowości i ulic z plików search.tsv zainstalowanych paczek (działa offline). */
class SearchIndex {
    private val entries = ArrayList<PlaceHit>()
    private val norm = ArrayList<String>()
    @Volatile var ready = false
        private set

    fun load(files: List<File>) {
        val e = ArrayList<PlaceHit>()
        val n = ArrayList<String>()
        for (f in files) {
            if (!f.exists()) continue
            f.forEachLine { line ->
                val p = line.split('\t')
                if (p.size >= 4) {
                    val lat = p[2].toDoubleOrNull()
                    val lon = p[3].toDoubleOrNull()
                    if (lat != null && lon != null) {
                        val hit = PlaceHit(p[0], p[1], lat, lon, p.getOrElse(4) { "" })
                        e.add(hit)
                        n.add(normalize(hit.name + " " + hit.context))
                    }
                }
            }
        }
        synchronized(this) {
            entries.clear(); entries.addAll(e)
            norm.clear(); norm.addAll(n)
        }
        ready = true
    }

    @Synchronized
    fun search(query: String, limit: Int = 30, nearLat: Double? = null, nearLon: Double? = null): List<PlaceHit> {
        val q = normalize(query).trim()
        if (q.length < 2) return emptyList()
        val words = q.split(' ').filter { it.isNotEmpty() }
        val scored = ArrayList<Pair<Double, PlaceHit>>()
        for (i in entries.indices) {
            val s = norm[i]
            if (!words.all { s.contains(it) }) continue
            val hit = entries[i]
            var score = 0.0
            val nn = normalize(hit.name)
            if (nn == q) score -= 50.0 else if (nn.startsWith(words[0])) score -= 20.0
            score += when (hit.kind) { "miasto" -> -15.0; "dzielnica" -> -8.0; "wieś" -> -5.0; else -> 0.0 }
            if (nearLat != null && nearLon != null) {
                score += Geo.dist(nearLat, nearLon, hit.lat, hit.lon) / 20000.0
            }
            scored.add(score to hit)
        }
        scored.sortBy { it.first }
        return scored.take(limit).map { it.second }
    }

    companion object {
        fun normalize(s: String): String {
            val t = s.lowercase().replace('ł', 'l').replace('đ', 'd')
            return Normalizer.normalize(t, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        }
    }
}
