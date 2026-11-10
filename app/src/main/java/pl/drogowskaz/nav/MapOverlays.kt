package pl.drogowskaz.nav

import android.graphics.Color
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** Nakładki mapy (trasa, pozycja pojazdu) - wersja używana na ekranie samochodu. */
object MapOverlays {
    fun install(st: Style, pal: Palette) {
        st.addImage("nav-arrow", Icons.carArrow(96, if (pal.night) 0xFF6AA9FF.toInt() else 0xFF1A73E8.toInt(),
            if (pal.night) 0xFF0F2C66.toInt() else 0xFFFFFFFF.toInt()))
        st.addSource(GeoJsonSource("route-src"))
        st.addSource(GeoJsonSource("dest-src"))
        st.addSource(GeoJsonSource("me-src"))
        st.addLayer(LineLayer("route-casing", "route-src").withProperties(
            PropertyFactory.lineColor(pal.routeCasing), PropertyFactory.lineWidth(10f),
            PropertyFactory.lineCap("round"), PropertyFactory.lineJoin("round")))
        st.addLayer(LineLayer("route-line", "route-src").withProperties(
            PropertyFactory.lineColor(pal.route), PropertyFactory.lineWidth(6.5f),
            PropertyFactory.lineCap("round"), PropertyFactory.lineJoin("round")))
        st.addLayer(CircleLayer("dest-circle", "dest-src").withProperties(
            PropertyFactory.circleRadius(9f), PropertyFactory.circleColor(0xFFE53935.toInt()),
            PropertyFactory.circleStrokeColor(Color.WHITE), PropertyFactory.circleStrokeWidth(3f)))
        st.addLayer(SymbolLayer("me-arrow", "me-src").withProperties(
            PropertyFactory.iconImage("nav-arrow"), PropertyFactory.iconRotate(Expression.get("bearing")),
            PropertyFactory.iconRotationAlignment("map"), PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true), PropertyFactory.iconSize(0.7f)))
    }

    fun setRoute(st: Style?, r: Route?) {
        val src = st?.getSourceAs<GeoJsonSource>("route-src") ?: return
        val dst = st.getSourceAs<GeoJsonSource>("dest-src")
        if (r == null) {
            src.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
            dst?.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
            return
        }
        val pts = ArrayList<Point>(r.lats.size)
        for (i in r.lats.indices) pts.add(Point.fromLngLat(r.lons[i], r.lats[i]))
        src.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(pts)))
        dst?.setGeoJson(Point.fromLngLat(r.lons.last(), r.lats.last()))
    }

    fun setMe(st: Style?, lat: Double, lon: Double, bearing: Double) {
        val src = st?.getSourceAs<GeoJsonSource>("me-src") ?: return
        val f = Feature.fromGeometry(Point.fromLngLat(lon, lat))
        f.addNumberProperty("bearing", bearing)
        src.setGeoJson(f)
    }
}
