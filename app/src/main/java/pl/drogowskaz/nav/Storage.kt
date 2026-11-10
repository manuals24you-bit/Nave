package pl.drogowskaz.nav

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class SavedPlace(val id: Long, val name: String, val lat: Double, val lon: Double, val kind: String = "fav")

/** Punkt trasy: start/przystanek/cel. */
class RoutePoint(val name: String, val lat: Double, val lon: Double)

class SavedRoute(val id: Long, val name: String, val profile: Profile, val points: List<RoutePoint>)

/** Zapisane miejsca, trasy i ostatnie cele (plik JSON w pamięci aplikacji). */
class Storage(ctx: Context) {
    private val file = File(ctx.filesDir, "saved.json")
    val places = ArrayList<SavedPlace>()
    val routes = ArrayList<SavedRoute>()
    val recent = ArrayList<SavedPlace>()
    val reports = ArrayList<Hazard>()

    init { load() }

    @Synchronized
    private fun load() {
        if (!file.exists()) return
        runCatching {
            val o = JSONObject(file.readText())
            o.optJSONArray("places")?.let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).let {
                    places.add(SavedPlace(it.getLong("id"), it.getString("name"), it.getDouble("lat"), it.getDouble("lon"), it.optString("kind", "fav")))
                }
            }
            o.optJSONArray("recent")?.let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).let {
                    recent.add(SavedPlace(it.getLong("id"), it.getString("name"), it.getDouble("lat"), it.getDouble("lon"), "recent"))
                }
            }
            o.optJSONArray("reports")?.let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).let {
                    reports.add(Hazard(it.getString("kind"), it.getDouble("lat"), it.getDouble("lon"), 0, it.getLong("ts")))
                }
            }
            o.optJSONArray("routes")?.let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).let { r ->
                    val pts = ArrayList<RoutePoint>()
                    val pa = r.getJSONArray("points")
                    for (j in 0 until pa.length()) pa.getJSONObject(j).let { pts.add(RoutePoint(it.getString("name"), it.getDouble("lat"), it.getDouble("lon"))) }
                    routes.add(SavedRoute(r.getLong("id"), r.getString("name"), Profile.valueOf(r.getString("profile")), pts))
                }
            }
        }
    }

    @Synchronized
    private fun save() {
        val o = JSONObject()
        o.put("places", JSONArray().also { a ->
            places.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("lat", it.lat).put("lon", it.lon).put("kind", it.kind)) }
        })
        o.put("recent", JSONArray().also { a ->
            recent.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("lat", it.lat).put("lon", it.lon)) }
        })
        o.put("reports", JSONArray().also { a ->
            reports.forEach { a.put(JSONObject().put("kind", it.kind).put("lat", it.lat).put("lon", it.lon).put("ts", it.createdMs)) }
        })
        o.put("routes", JSONArray().also { a ->
            routes.forEach { r ->
                a.put(JSONObject().put("id", r.id).put("name", r.name).put("profile", r.profile.name).put("points", JSONArray().also { pa ->
                    r.points.forEach { pa.put(JSONObject().put("name", it.name).put("lat", it.lat).put("lon", it.lon)) }
                }))
            }
        })
        val tmp = File(file.path + ".tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(file)
    }

    fun addPlace(name: String, lat: Double, lon: Double, kind: String = "fav") {
        if (kind == "home" || kind == "work") places.removeAll { it.kind == kind }
        places.add(SavedPlace(System.currentTimeMillis(), name, lat, lon, kind))
        save()
    }

    fun removePlace(id: Long) { places.removeAll { it.id == id }; save() }

    fun addRecent(name: String, lat: Double, lon: Double) {
        recent.removeAll { Geo.dist(it.lat, it.lon, lat, lon) < 30 }
        recent.add(0, SavedPlace(System.currentTimeMillis(), name, lat, lon, "recent"))
        while (recent.size > 15) recent.removeAt(recent.size - 1)
        save()
    }

    fun addRoute(name: String, profile: Profile, points: List<RoutePoint>) {
        routes.add(SavedRoute(System.currentTimeMillis(), name, profile, points))
        save()
    }

    fun removeRoute(id: Long) { routes.removeAll { it.id == id }; save() }

    /** Zgłoszenie zagrożenia w danym miejscu (zostaje aktualne ograniczony czas). */
    fun addReport(kind: String, lat: Double, lon: Double) {
        reports.add(Hazard(kind, lat, lon, 0, System.currentTimeMillis()))
        purgeReports()
        save()
    }

    fun purgeReports() {
        val now = System.currentTimeMillis()
        reports.removeAll { now - it.createdMs > Hazard.ttl(it.kind) }
    }

    fun activeReports(): List<Hazard> { purgeReports(); return reports.toList() }

    fun home() = places.firstOrNull { it.kind == "home" }
    fun work() = places.firstOrNull { it.kind == "work" }
}
