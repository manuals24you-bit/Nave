package pl.drogowskaz.nav

import org.json.JSONArray
import org.json.JSONObject

/** Generuje styl mapy (schemat OpenMapTiles) w wersji dziennej i nocnej. */
object MapStyle {

    private class C(
        val bg: String, val water: String, val wood: String, val grass: String, val farm: String,
        val park: String, val residential: String, val industrial: String, val building: String,
        val motorway: String, val motorwayCase: String, val trunk: String, val trunkCase: String,
        val primary: String, val primaryCase: String, val secondary: String, val secondaryCase: String,
        val minor: String, val minorCase: String, val service: String,
        val track: String, val foot: String, val cycle: String, val rail: String,
        val text: String, val halo: String, val boundary: String,
    )

    private val DAY = C(
        bg = "#f2efe9", water = "#a8d4f0", wood = "#c9e2b3", grass = "#dcedc8", farm = "#f1ecd7",
        park = "#c8e6b2", residential = "#ebe7df", industrial = "#e2dfe6", building = "#d8d1c4",
        motorway = "#f6a25a", motorwayCase = "#c06f27", trunk = "#f8c46b", trunkCase = "#c39a3b",
        primary = "#fbd98f", primaryCase = "#c9a252", secondary = "#fff1b5", secondaryCase = "#cdbf84",
        minor = "#ffffff", minorCase = "#cdc7bb", service = "#ffffff",
        track = "#a5855a", foot = "#d9602a", cycle = "#1672e6", rail = "#8b8b8b",
        text = "#2b2f33", halo = "#ffffff", boundary = "#9a7fb5",
    )

    private val NIGHT = C(
        bg = "#14181d", water = "#0f2a3f", wood = "#18281c", grass = "#1a2a1e", farm = "#1b1f1a",
        park = "#1a2b1f", residential = "#1a1f25", industrial = "#1d1d26", building = "#262e38",
        motorway = "#b0642a", motorwayCase = "#4f2e12", trunk = "#a47c38", trunkCase = "#4a3a18",
        primary = "#8c7542", primaryCase = "#3f3520", secondary = "#6d6c54", secondaryCase = "#34342a",
        minor = "#3b444f", minorCase = "#0f1318", service = "#2f363e",
        track = "#7a6845", foot = "#d17042", cycle = "#5a98ff", rail = "#58606b",
        text = "#c9d1da", halo = "#14181d", boundary = "#6c5a85",
    )

    private fun arr(vararg v: Any?) = JSONArray().also { a -> v.forEach { a.put(it) } }
    private fun obj(vararg kv: Pair<String, Any?>) = JSONObject().also { o -> kv.forEach { o.put(it.first, it.second) } }
    private fun get(k: String) = arr("get", k)
    private fun eq(k: String, v: String) = arr("==", get(k), v)
    private fun inSet(k: String, vararg v: String) = arr("match", get(k), JSONArray().also { a -> v.forEach { a.put(it) } }, true, false)

    /** Interpolacja szerokości po zoomie: pary (zoom, szerokość). */
    private fun zoomWidth(vararg zw: Pair<Int, Double>): JSONArray {
        val a = arr("interpolate", arr("exponential", 1.4), arr("zoom"))
        zw.forEach { a.put(it.first); a.put(it.second) }
        return a
    }

    fun build(port: Int, regionIds: List<String>, night: Boolean): String {
        val c = if (night) NIGHT else DAY
        val sources = JSONObject()
        val layers = JSONArray()
        layers.put(obj("id" to "background", "type" to "background", "paint" to obj("background-color" to c.bg)))

        for (id in regionIds) {
            sources.put("omt_$id", obj(
                "type" to "vector",
                "url" to "pmtiles://http://127.0.0.1:$port/tiles/$id.pmtiles",
            ))
        }
        // Najpierw wszystkie wypełnienia, potem drogi, na końcu etykiety - każdy region osobno
        // zachowałby własną kolejność warstw, dlatego grupujemy po rodzaju.
        val fills = ArrayList<JSONObject>()
        val roadsCase = ArrayList<JSONObject>()
        val roadsFill = ArrayList<JSONObject>()
        val labels = ArrayList<JSONObject>()

        for (id in regionIds) {
            val s = "omt_$id"
            fills.add(layer("lc_$id", "fill", s, "landcover", null,
                paint = obj("fill-color" to arr("match", get("class"),
                    "wood", c.wood, "forest", c.wood, "grass", c.grass, "farmland", c.farm, "sand", c.farm, c.grass))))
            fills.add(layer("lu_$id", "fill", s, "landuse", null,
                paint = obj("fill-color" to arr("match", get("class"),
                    "residential", c.residential, "industrial", c.industrial, "commercial", c.industrial,
                    "cemetery", c.wood, "pitch", c.park, "playground", c.park, "stadium", c.park, "rgba(0,0,0,0)"))))
            fills.add(layer("park_$id", "fill", s, "park", null, paint = obj("fill-color" to c.park, "fill-opacity" to 0.8)))
            fills.add(layer("water_$id", "fill", s, "water", null, paint = obj("fill-color" to c.water)))
            fills.add(layer("ww_$id", "line", s, "waterway", null,
                paint = obj("line-color" to c.water, "line-width" to zoomWidth(8 to 0.5, 14 to 2.0, 18 to 6.0))))
            fills.add(layer("bld_$id", "fill", s, "building", null, minzoom = 13.0,
                paint = obj("fill-color" to c.building, "fill-opacity" to 0.9)))
            fills.add(layer("rail_$id", "line", s, "transportation", eq("class", "rail"), minzoom = 10.0,
                paint = obj("line-color" to c.rail, "line-width" to 1.0, "line-dasharray" to arr(4, 3))))

            val groups = listOf(
                Triple("motorway", c.motorway to c.motorwayCase, doubleArrayOf(1.2, 3.0, 7.0, 18.0)),
                Triple("trunk", c.trunk to c.trunkCase, doubleArrayOf(1.0, 2.6, 6.0, 16.0)),
                Triple("primary", c.primary to c.primaryCase, doubleArrayOf(0.8, 2.4, 5.5, 15.0)),
                Triple("secondary", c.secondary to c.secondaryCase, doubleArrayOf(0.6, 2.0, 5.0, 14.0)),
                Triple("tertiary", c.minor to c.minorCase, doubleArrayOf(0.0, 1.6, 4.5, 12.0)),
                Triple("minor", c.minor to c.minorCase, doubleArrayOf(0.0, 1.0, 3.5, 10.0)),
                Triple("service", c.service to c.minorCase, doubleArrayOf(0.0, 0.0, 2.0, 6.0)),
            )
            for ((cls, colors, w) in groups) {
                val minZ = if (w[0] == 0.0) (if (w[1] == 0.0) 14.0 else 11.0) else 5.0
                val filter = eq("class", cls)
                roadsCase.add(layer("rc_${cls}_$id", "line", s, "transportation", filter, minzoom = minZ,
                    layout = obj("line-cap" to "round", "line-join" to "round"),
                    paint = obj("line-color" to colors.second,
                        "line-width" to zoomWidth(6 to w[0] + 0.6, 10 to w[1] + 1.0, 14 to w[2] + 1.6, 18 to w[3] + 2.4))))
                roadsFill.add(layer("rf_${cls}_$id", "line", s, "transportation", filter, minzoom = minZ,
                    layout = obj("line-cap" to "round", "line-join" to "round"),
                    paint = obj("line-color" to colors.first,
                        "line-width" to zoomWidth(6 to w[0], 10 to w[1], 14 to w[2], 18 to w[3]))))
            }
            // Ścieżki rowerowe, piesze, drogi gruntowe - wyraźnie wyróżnione
            roadsFill.add(layer("track_$id", "line", s, "transportation", eq("class", "track"), minzoom = 12.0,
                paint = obj("line-color" to c.track, "line-width" to zoomWidth(12 to 0.8, 16 to 2.0),
                    "line-dasharray" to arr(3, 2))))
            roadsFill.add(layer("foot_$id", "line", s, "transportation",
                arr("all", eq("class", "path"), arr("!", inSet("subclass", "cycleway"))), minzoom = 13.0,
                paint = obj("line-color" to c.foot, "line-width" to zoomWidth(13 to 0.8, 18 to 2.5),
                    "line-dasharray" to arr(2, 2))))
            roadsFill.add(layer("cycle_$id", "line", s, "transportation",
                arr("all", eq("class", "path"), eq("subclass", "cycleway")), minzoom = 12.0,
                paint = obj("line-color" to c.cycle, "line-width" to zoomWidth(12 to 1.0, 18 to 3.0),
                    "line-dasharray" to arr(3, 1.5))))

            labels.add(layer("bound_$id", "line", s, "boundary", arr("<=", get("admin_level"), 4), minzoom = 4.0,
                paint = obj("line-color" to c.boundary, "line-width" to 1.0, "line-dasharray" to arr(4, 3))))
            labels.add(layer("roadname_$id", "symbol", s, "transportation_name", null, minzoom = 12.5,
                layout = obj("symbol-placement" to "line", "text-field" to arr("coalesce", get("name:pl"), get("name")),
                    "text-font" to arr("Noto Sans Regular"), "text-size" to 11, "text-max-angle" to 30),
                paint = obj("text-color" to c.text, "text-halo-color" to c.halo, "text-halo-width" to 1.5)))
            labels.add(layer("place_$id", "symbol", s, "place",
                inSet("class", "city", "town", "village", "suburb", "hamlet", "neighbourhood"),
                layout = obj("text-field" to arr("coalesce", get("name:pl"), get("name")),
                    "text-font" to arr("Noto Sans Regular"),
                    "text-size" to arr("match", get("class"), "city", 19, "town", 16, "village", 14, "suburb", 12, 11),
                    "text-max-width" to 8),
                paint = obj("text-color" to c.text, "text-halo-color" to c.halo, "text-halo-width" to 1.6)))
        }
        fills.forEach { layers.put(it) }
        roadsCase.forEach { layers.put(it) }
        roadsFill.forEach { layers.put(it) }
        labels.forEach { layers.put(it) }

        return obj(
            "version" to 8,
            "name" to if (night) "Drogowskaz noc" else "Drogowskaz dzień",
            "glyphs" to "http://127.0.0.1:$port/fonts/{fontstack}/{range}.pbf",
            "sources" to sources,
            "layers" to layers,
        ).toString()
    }

    private fun layer(
        id: String, type: String, source: String, sourceLayer: String, filter: JSONArray?,
        minzoom: Double? = null, layout: JSONObject? = null, paint: JSONObject? = null,
    ): JSONObject {
        val o = obj("id" to id, "type" to type, "source" to source, "source-layer" to sourceLayer)
        if (filter != null) o.put("filter", filter)
        if (minzoom != null) o.put("minzoom", minzoom)
        if (layout != null) o.put("layout", layout)
        if (paint != null) o.put("paint", paint)
        return o
    }
}
