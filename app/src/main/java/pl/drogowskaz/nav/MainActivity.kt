package pl.drogowskaz.nav

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), NavListener {

    private enum class UiMode { BROWSE, PLACE, PREVIEW, NAV }

    // --- dane i silniki ---
    private lateinit var engine: Engine
    private val storage get() = engine.storage
    private val dataManager get() = engine.dataManager
    private val tileServer get() = engine.tileServer
    private val navigator get() = engine.navigator
    private val searchIndex get() = engine.searchIndex
    private val bg = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    // --- mapa ---
    private lateinit var mapView: MapView
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var styleNight: Boolean? = null
    private var styleSignature = ""

    // --- stan ---
    private var uiMode = UiMode.BROWSE
    private var pal = Palette.DAY
    private var follow = true
    private var voiceOn: Boolean
        get() = engine.voiceOn
        set(v) { engine.voiceOn = v }
    private val lastLat get() = engine.lastLat
    private val lastLon get() = engine.lastLon
    private var placeName = ""
    private var placeLat = 0.0
    private var placeLon = 0.0
    private var previewProfile = Profile.CAR_FASTEST
    private var previewRoute: Route? = null
    private val tts get() = engine.tts
    private val ttsReady get() = engine.ttsReady
    private var simulating: Boolean
        get() = engine.simulating
        set(v) { engine.simulating = v }

    // --- widoki ---
    private val hooks = ArrayList<() -> Unit>()
    private lateinit var overlay: FrameLayout
    private lateinit var banner: LinearLayout
    private lateinit var arrow: TurnArrowView
    private lateinit var bannerDist: TextView
    private lateinit var bannerStreet: TextView
    private lateinit var bannerThen: TextView
    private lateinit var statusText: TextView
    private lateinit var speedView: SpeedView
    private lateinit var limitView: LimitSignView
    private lateinit var streetChip: TextView
    private lateinit var hazardChip: TextView
    private lateinit var navBar: LinearLayout
    private lateinit var navInfo: TextView
    private lateinit var placeSheet: LinearLayout
    private lateinit var placeTitle: TextView
    private lateinit var previewSheet: LinearLayout
    private lateinit var previewInfo: TextView
    private lateinit var chipRow: LinearLayout
    private lateinit var speedCluster: LinearLayout
    private var insetTop = 0
    private var insetBottom = 0


    // =====================================================================
    //  cykl życia
    // =====================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        MapLibre.getInstance(this)

        engine = Engine.get(this)
        engine.addListener(this)

        pal = if (ThemeManager.isNight(this, null, null)) Palette.NIGHT else Palette.DAY

        mapView = MapView(this)
        val root = FrameLayout(this)
        root.addView(mapView, FrameLayout.LayoutParams(-1, -1))
        overlay = FrameLayout(this)
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        buildUi()
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            insetTop = sb.top; insetBottom = sb.bottom
            overlay.setPadding(0, insetTop, 0, insetBottom)
            insets
        }

        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { m ->
            map = m
            m.uiSettings.isCompassEnabled = false
            m.uiSettings.isAttributionEnabled = true
            m.uiSettings.isLogoEnabled = false
            m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(52.0, 19.4), 5.5))
            m.addOnMapLongClickListener { ll ->
                onMapPicked(ll.latitude, ll.longitude); true
            }
            m.addOnMapClickListener { ll ->
                if (uiMode == UiMode.PLACE) { setUiMode(UiMode.BROWSE); clearMarker() }
                false
            }
            m.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE && follow) {
                    follow = false
                }
            }
            reloadPacks(firstRun = true)
        }

        requestLocation()
        main.post(ticker)
    }

    private val ticker = object : Runnable {
        var n = 0
        override fun run() {
            if (++n % 120 == 0) checkTheme()   // co ok. minutę
            main.postDelayed(this, 500)
        }
    }

    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() { super.onResume(); mapView.onResume(); checkTheme() }
    override fun onPause() { super.onPause(); mapView.onPause() }
    override fun onStop() { super.onStop(); mapView.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); mapView.onSaveInstanceState(outState) }
    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        engine.removeListener(this)
        mapView.onDestroy()
        super.onDestroy()
    }

    // =====================================================================
    //  lokalizacja
    // =====================================================================

    private fun requestLocation() {
        if (!engine.startLocation()) {
            val perms = if (android.os.Build.VERSION.SDK_INT >= 33)
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)
            else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            ActivityCompat.requestPermissions(this, perms, 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) requestLocation()
        else toast("Bez zgody na lokalizację nawigacja nie zadziała")
    }

    // =====================================================================
    //  dane: paczki, graf, wyszukiwanie, styl
    // =====================================================================

    private fun installedWithMaps() = dataManager.installed().filter { it.hasMap }

    private fun reloadPacks(firstRun: Boolean = false) {
        engine.reloadPacks()
        applyMapStyle(force = true)
        if (firstRun && dataManager.installed().isEmpty()) {
            main.postDelayed({
                AlertDialog.Builder(this, dialogTheme())
                    .setTitle("Pobierz mapę offline")
                    .setMessage("Aby korzystać z nawigacji bez internetu, pobierz mapę swojego województwa oraz paczkę „Polska – trasy” (podróże między województwami).")
                    .setPositiveButton("Pobierz") { _, _ -> showDownloads() }
                    .setNegativeButton("Później", null).show()
            }, 800)
        }
    }

    private fun graphFor(lat: Double, lon: Double): RoadGraph? = engine.graphFor(lat, lon)

    private fun dialogTheme() =
        if (pal.night) androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert
        else androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert

    // =====================================================================
    //  motyw dzień/noc
    // =====================================================================

    private fun checkTheme() {
        val night = ThemeManager.isNight(this, lastLat, lastLon)
        if (night != pal.night) {
            pal = if (night) Palette.NIGHT else Palette.DAY
            hooks.forEach { it() }
            applyMapStyle(force = false)
        }
    }

    private fun applyMapStyle(force: Boolean) {
        val m = map ?: return
        val night = pal.night
        val ids = installedWithMaps().map { it.id }
        val sig = ids.joinToString(",") + "|" + night
        if (!force && sig == styleSignature) return
        styleSignature = sig
        val json = MapStyle.build(tileServer.port, ids, night)
        m.setStyle(Style.Builder().fromJson(json)) { st ->
            style = st
            styleNight = night
            installOverlays(st)
            refreshOverlays()
        }
    }

    // =====================================================================
    //  nakładki na mapie
    // =====================================================================

    private fun installOverlays(st: Style) {
        st.addImage("nav-arrow", Icons.carArrow(96, if (pal.night) 0xFF6AA9FF.toInt() else 0xFF1A73E8.toInt(),
            if (pal.night) 0xFF0F2C66.toInt() else 0xFFFFFFFF.toInt()))
        st.addSource(GeoJsonSource("route-src"))
        st.addSource(GeoJsonSource("dest-src"))
        st.addSource(GeoJsonSource("saved-src"))
        st.addSource(GeoJsonSource("me-src"))
        st.addSource(GeoJsonSource("hazard-src"))
        st.addLayer(LineLayer("route-casing", "route-src").withProperties(
            PropertyFactory.lineColor(pal.routeCasing), PropertyFactory.lineWidth(10f),
            PropertyFactory.lineCap("round"), PropertyFactory.lineJoin("round")))
        st.addLayer(LineLayer("route-line", "route-src").withProperties(
            PropertyFactory.lineColor(pal.route), PropertyFactory.lineWidth(6.5f),
            PropertyFactory.lineCap("round"), PropertyFactory.lineJoin("round")))
        st.addLayer(CircleLayer("saved-circle", "saved-src").withProperties(
            PropertyFactory.circleRadius(6f), PropertyFactory.circleColor(0xFF8E24AA.toInt()),
            PropertyFactory.circleStrokeColor(Color.WHITE), PropertyFactory.circleStrokeWidth(2f)))
        st.addLayer(SymbolLayer("saved-label", "saved-src").withProperties(
            PropertyFactory.textField(Expression.get("name")), PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
            PropertyFactory.textSize(12f), PropertyFactory.textOffset(arrayOf(0f, 1.2f)),
            PropertyFactory.textColor(if (pal.night) 0xFFE1BEE7.toInt() else 0xFF6A1B9A.toInt()),
            PropertyFactory.textHaloColor(if (pal.night) 0xFF14181D.toInt() else Color.WHITE),
            PropertyFactory.textHaloWidth(1.5f)))
        st.addLayer(CircleLayer("hazard-circle", "hazard-src").withProperties(
            PropertyFactory.circleRadius(7f), PropertyFactory.circleColor(0xFFFB8C00.toInt()),
            PropertyFactory.circleStrokeColor(Color.WHITE), PropertyFactory.circleStrokeWidth(2f)))
        st.addLayer(SymbolLayer("hazard-label", "hazard-src").withProperties(
            PropertyFactory.textField(Expression.get("label")), PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
            PropertyFactory.textSize(12f), PropertyFactory.textOffset(arrayOf(0f, 1.3f)),
            PropertyFactory.textColor(if (pal.night) 0xFFFFCC80.toInt() else 0xFFE65100.toInt()),
            PropertyFactory.textHaloColor(if (pal.night) 0xFF14181D.toInt() else Color.WHITE),
            PropertyFactory.textHaloWidth(1.5f)))
        st.addLayer(CircleLayer("dest-circle", "dest-src").withProperties(
            PropertyFactory.circleRadius(9f), PropertyFactory.circleColor(0xFFE53935.toInt()),
            PropertyFactory.circleStrokeColor(Color.WHITE), PropertyFactory.circleStrokeWidth(3f)))
        st.addLayer(SymbolLayer("me-arrow", "me-src").withProperties(
            PropertyFactory.iconImage("nav-arrow"), PropertyFactory.iconRotate(Expression.get("bearing")),
            PropertyFactory.iconRotationAlignment("map"), PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true), PropertyFactory.iconSize(0.7f)))
    }

    private fun refreshOverlays() {
        val st = style ?: return
        val r = navigator.route ?: previewRoute
        setRouteGeometry(r)
        refreshSaved()
        refreshHazards()
        if (uiMode == UiMode.PLACE || uiMode == UiMode.PREVIEW || uiMode == UiMode.NAV) {
            st.getSourceAs<GeoJsonSource>("dest-src")?.setGeoJson(Point.fromLngLat(placeLon, placeLat))
        }
        lastGuidance?.let { updateMeMarker(it.lat, it.lon, it.bearing) }
    }

    private var lastGuidance: Guidance? = null

    private fun setRouteGeometry(r: Route?) {
        val src = style?.getSourceAs<GeoJsonSource>("route-src") ?: return
        if (r == null) { src.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>())); return }
        val pts = ArrayList<Point>(r.lats.size)
        for (i in r.lats.indices) pts.add(Point.fromLngLat(r.lons[i], r.lats[i]))
        src.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(pts)))
    }

    private fun refreshSaved() {
        val src = style?.getSourceAs<GeoJsonSource>("saved-src") ?: return
        val f = storage.places.map {
            Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)).also { ft -> ft.addStringProperty("name", it.name) }
        }
        src.setGeoJson(FeatureCollection.fromFeatures(f))
    }

    private fun refreshHazards() {
        val src = style?.getSourceAs<GeoJsonSource>("hazard-src") ?: return
        val f = storage.activeReports().map {
            Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)).also { ft -> ft.addStringProperty("label", it.label()) }
        }
        src.setGeoJson(FeatureCollection.fromFeatures(f))
    }

    private fun showReportDialog() {
        val kinds = Hazard.REPORT_KINDS
        AlertDialog.Builder(this, dialogTheme()).setTitle("Zgłoś w tym miejscu")
            .setItems(kinds.map { it.second }.toTypedArray()) { _, i ->
                if (engine.report(kinds[i].first)) {
                    refreshHazards()
                    toast("Zapisano: ${kinds[i].second}. Aplikacja ostrzeże Cię tutaj przy następnych przejazdach.")
                } else toast("Brak pozycji GPS")
            }.show()
    }

    private fun showDataUrlDialog() {
        val et = EditText(this).apply {
            setText(dataManager.baseUrl); setSingleLine(); setSelection(text.length)
            hint = "https://github.com/UŻYTKOWNIK/REPO/releases/download/dane"
        }
        AlertDialog.Builder(this, dialogTheme()).setTitle("Adres serwera map")
            .setMessage("Folder z plikami index.json i paczkami (np. wydanie „dane” na GitHubie albo serwer w sieci domowej).")
            .setView(et)
            .setPositiveButton("Zapisz") { _, _ -> dataManager.baseUrl = et.text.toString(); toast("Zapisano") }
            .setNegativeButton("Anuluj", null).show()
    }

    private fun updateMeMarker(lat: Double, lon: Double, bearing: Double) {
        val src = style?.getSourceAs<GeoJsonSource>("me-src") ?: return
        val f = Feature.fromGeometry(Point.fromLngLat(lon, lat))
        f.addNumberProperty("bearing", bearing)
        src.setGeoJson(f)
    }

    private fun clearMarker() {
        style?.getSourceAs<GeoJsonSource>("dest-src")?.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
    }

    // =====================================================================
    //  NavListener
    // =====================================================================

    override fun onGuidance(g: Guidance) {
        lastGuidance = g
        updateMeMarker(g.lat, g.lon, g.bearing)

        speedView.speed = g.speedKmh
        speedView.gpsLost = g.gpsLost
        speedView.over = g.limitKmh > 0 && g.speedKmh > g.limitKmh
        speedView.invalidate()
        limitView.limit = g.limitKmh
        streetChip.text = g.street ?: ""
        streetChip.visibility = if (g.street.isNullOrBlank()) View.GONE else View.VISIBLE
        val hz = g.hazard
        if (hz != null) {
            hazardChip.text = "${hz.icon()} ${hz.label()} • ${formatDist(g.hazardDistance)}"
            hazardChip.visibility = View.VISIBLE
        } else hazardChip.visibility = View.GONE

        if (navigator.navigating && g.next != null) {
            val n = g.next
            arrow.turn = n.turn
            arrow.exit = n.roundaboutExit
            bannerDist.text = formatDist(g.nextDistance)
            bannerStreet.text = n.text()
            bannerThen.text = g.thenManeuver?.takeIf { it.turn != Turn.ARRIVE }?.let { "Potem: " + it.text() } ?: ""
            bannerThen.visibility = if (bannerThen.text.isEmpty()) View.GONE else View.VISIBLE
            val eta = SimpleDateFormat("HH:mm", Locale("pl")).format(Date(System.currentTimeMillis() + (g.remainingSeconds * 1000).toLong()))
            navInfo.text = "${formatDuration(g.remainingSeconds)}  •  ${formatDist(g.remainingMeters)}  •  $eta" +
                if (g.offRoute) "\nPoza trasą…" else ""
        }

        if (follow) followCamera(g)
    }

    private fun followCamera(g: Guidance) {
        val m = map ?: return
        val nav = navigator.navigating
        val zoom = if (nav) (17.3 - g.speedKmh / 120.0 * 2.4).coerceIn(14.6, 17.3)
        else m.cameraPosition.zoom.coerceAtLeast(14.5)
        val bearing = if (nav || g.speedKmh > 6) g.bearing else m.cameraPosition.bearing
        val cam = CameraPosition.Builder().target(LatLng(g.lat, g.lon)).zoom(zoom).bearing(bearing)
            .tilt(if (nav) 55.0 else 0.0)
            .padding(0.0, mapView.height * (if (nav) 0.30 else 0.0), 0.0, 0.0).build()
        m.easeCamera(CameraUpdateFactory.newCameraPosition(cam), 900)
    }

    override fun onRouteChanged(route: Route?) {
        setRouteGeometry(route ?: previewRoute)
    }

    override fun onSpeak(text: String) {}

    private fun voicePrefs() = engine.voicePrefs()
    private fun friendlyOn() = engine.friendlyOn()
    private fun speakSample() = engine.speakSample()

    private fun showVoiceDialog() {
        if (tts == null || !ttsReady) {
            toast("Brak polskiego głosu. Zainstaluj „Usługi mowy Google” i pobierz polski głos w ustawieniach systemu.")
            return
        }
        val presets = listOf("sweet" to "Słodki (wyższy, łagodny)", "soft" to "Łagodny (spokojny, wolniejszy)",
            "lively" to "Żywy (pogodny)", "natural" to "Naturalny")
        val cur = voicePrefs().getString("voicePreset", "sweet")
        val items = arrayOf(
            "Wybierz głos…",
            "Ton: ${presets.first { it.first == cur }.second}",
            "Styl komunikatów: ${if (friendlyOn()) "przyjazny" else "zwięzły"}",
            "Posłuchaj próbki",
        )
        AlertDialog.Builder(this, dialogTheme()).setTitle("Głos asystentki").setItems(items) { _, i ->
            when (i) {
                0 -> showVoiceList()
                1 -> AlertDialog.Builder(this, dialogTheme()).setTitle("Ton głosu")
                    .setSingleChoiceItems(presets.map { it.second }.toTypedArray(), presets.indexOfFirst { it.first == cur }) { d, j ->
                        voicePrefs().edit().putString("voicePreset", presets[j].first).apply()
                        d.dismiss(); speakSample()
                    }.show()
                2 -> {
                    voicePrefs().edit().putBoolean("friendly", !friendlyOn()).apply()
                    speakSample()
                }
                3 -> speakSample()
            }
        }.setNegativeButton("Zamknij", null).show()
    }

    private fun showVoiceList() {
        val t = tts ?: return
        val voices = (t.voices ?: emptySet<Voice>()).filter { it.locale.language == "pl" }.sortedBy { it.name }
        if (voices.isEmpty()) {
            toast("Nie znaleziono polskich głosów. Pobierz je w ustawieniach: Usługi mowy Google → Zainstaluj dane głosowe.")
            return
        }
        val chosen = voicePrefs().getString("voiceName", null)
        val labels = voices.mapIndexed { i, v ->
            "Głos ${i + 1} — ${if (v.isNetworkConnectionRequired) "online" else "offline"}" +
                (if (v.quality >= Voice.QUALITY_HIGH) " ★" else "")
        }
        AlertDialog.Builder(this, dialogTheme())
            .setTitle("Wybierz głos (dotknij, aby odsłuchać)")
            .setSingleChoiceItems(labels.toTypedArray(), voices.indexOfFirst { it.name == chosen }) { _, i ->
                voicePrefs().edit().putString("voiceName", voices[i].name).apply()
                speakSample()
            }
            .setPositiveButton("Gotowe", null).show()
    }

    override fun onArrived() {
        simulating = false
        setUiMode(UiMode.BROWSE)
        previewRoute = null
        setRouteGeometry(null)
        clearMarker()
        toast("Dojechałeś do celu")
    }

    override fun onStatus(msg: String) = showStatus(msg)

    private fun showStatus(msg: String) {
        statusText.text = msg
        statusText.visibility = View.VISIBLE
        main.removeCallbacks(hideStatus)
        main.postDelayed(hideStatus, 3500)
    }

    private val hideStatus = Runnable { statusText.visibility = View.GONE }

    // =====================================================================
    //  wybór miejsca, podgląd trasy, start
    // =====================================================================

    private fun describePoint(lat: Double, lon: Double): String {
        val g = graphFor(lat, lon)
        if (g != null) {
            val s = MapMatcher(g).snapPoint(lat, lon, Profile.CAR_FASTEST, 80.0)
            val nm = s?.let { g.name(it.edge) }
            if (!nm.isNullOrBlank()) return nm
        }
        return String.format(Locale.US, "%.5f, %.5f", lat, lon)
    }

    private fun onMapPicked(lat: Double, lon: Double) {
        if (uiMode == UiMode.NAV) return
        selectPlace(describePoint(lat, lon), lat, lon, moveCamera = false)
    }

    private fun selectPlace(name: String, lat: Double, lon: Double, moveCamera: Boolean = true) {
        placeName = name; placeLat = lat; placeLon = lon
        previewRoute = null
        setRouteGeometry(null)
        style?.getSourceAs<GeoJsonSource>("dest-src")?.setGeoJson(Point.fromLngLat(lon, lat))
        placeTitle.text = name
        setUiMode(UiMode.PLACE)
        follow = false
        if (moveCamera) map?.easeCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), 15.0), 700)
    }

    private fun startPreview(p: Profile = previewProfile) {
        previewProfile = p
        val fromLat = lastLat ?: map?.cameraPosition?.target?.latitude
        val fromLon = lastLon ?: map?.cameraPosition?.target?.longitude
        if (fromLat == null || fromLon == null) { toast("Czekam na pozycję GPS…"); return }
        val g1 = graphFor(fromLat, fromLon)
        if (g1 == null) {
            toast("Brak pobranych danych tras dla Twojej okolicy. Pobierz mapę w menu.")
            return
        }
        val g2 = graphFor(placeLat, placeLon)
        if (g2 == null || g2 !== g1) {
            toast("Cel leży w innym regionie. Pobierz paczkę „Polska – trasy” albo region celu.")
            return
        }
        if (navigator.graph !== g1) navigator.graph = g1
        setUiMode(UiMode.PREVIEW)
        previewInfo.text = "Liczenie trasy…"
        refreshChips()
        navigator.computeRoute(fromLat, fromLon, placeLat, placeLon, p) { r, err ->
            if (uiMode != UiMode.PREVIEW) return@computeRoute
            if (r == null) {
                previewRoute = null; setRouteGeometry(null)
                previewInfo.text = err ?: "Brak trasy"
                return@computeRoute
            }
            previewRoute = r
            setRouteGeometry(r)
            previewInfo.text = "${formatDuration(r.duration)}  •  ${formatDist(r.distance)}"
            fitRoute(r)
        }
    }

    private fun fitRoute(r: Route) {
        val m = map ?: return
        val b = LatLngBounds.Builder()
        var i = 0
        val step = maxOf(1, r.lats.size / 400)
        while (i < r.lats.size) { b.include(LatLng(r.lats[i], r.lons[i])); i += step }
        b.include(LatLng(r.lats.last(), r.lons.last()))
        follow = false
        runCatching {
            m.easeCamera(CameraUpdateFactory.newLatLngBounds(b.build(), dp(48), dp(120) + insetTop, dp(48), dp(260)), 800)
        }
    }

    private fun startNavigation(simulate: Boolean = false) {
        val r = previewRoute ?: return
        storage.addRecent(placeName, placeLat, placeLon)
        navigator.start(r, placeLat, placeLon)
        previewRoute = null
        setUiMode(UiMode.NAV)
        follow = true
        if (simulate) startSimulation(r)
    }

    private fun stopNavigation() {
        simulating = false
        navigator.stop()
        previewRoute = null
        setRouteGeometry(null)
        clearMarker()
        setUiMode(UiMode.BROWSE)
        map?.let { m ->
            m.easeCamera(CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder().target(m.cameraPosition.target).zoom(m.cameraPosition.zoom)
                    .tilt(0.0).bearing(0.0).padding(0.0, 0.0, 0.0, 0.0).build()), 600)
        }
    }

    /** Tryb testowy: wirtualna jazda po trasie (z szumem GPS), do sprawdzania nawigacji bez wychodzenia z domu. */
    private fun startSimulation(r: Route) {
        simulating = true
        var d = 0.0
        val speed = when (r.profile) { Profile.FOOT -> 1.4; Profile.BIKE -> 5.0; else -> 14.0 }
        val rnd = java.util.Random(1)
        val run = object : Runnable {
            override fun run() {
                if (!simulating || !navigator.navigating) { simulating = false; return }
                d += speed
                val p = r.pointAt(d)
                val loc = Location("sim")
                loc.latitude = p[0] + rnd.nextGaussian() * 0.00003
                loc.longitude = p[1] + rnd.nextGaussian() * 0.00004
                loc.speed = speed.toFloat()
                loc.bearing = p[2].toFloat()
                loc.accuracy = 8f
                loc.time = System.currentTimeMillis()
                engine.onLocation(loc)
                main.postDelayed(this, 1000)
            }
        }
        main.post(run)
    }

    // =====================================================================
    //  interfejs: budowa
    // =====================================================================

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun hook(f: () -> Unit) { hooks.add(f); f() }

    private fun round(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }

    private fun text(sizeSp: Float, bold: Boolean = false, primary: Boolean = true): TextView = TextView(this).also { tv ->
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        if (bold) tv.setTypeface(tv.typeface, Typeface.BOLD)
        hook { tv.setTextColor(if (primary) pal.panelText else pal.panelSub) }
    }

    private fun panel(v: LinearLayout, radius: Int = 18): LinearLayout {
        v.setPadding(dp(14), dp(12), dp(14), dp(12))
        v.elevation = dp(6).toFloat()
        hook { v.background = round(pal.panel, radius) }
        return v
    }

    private fun roundButton(label: String, onClick: () -> Unit): TextView {
        val b = TextView(this)
        b.text = label
        b.gravity = Gravity.CENTER
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        b.elevation = dp(4).toFloat()
        hook { b.background = round(pal.button, 28); b.setTextColor(pal.buttonIcon) }
        b.setOnClickListener { onClick() }
        return b
    }

    private fun textButton(label: String, primary: Boolean, onClick: () -> Unit): TextView {
        val b = TextView(this)
        b.text = label
        b.gravity = Gravity.CENTER
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        b.setTypeface(b.typeface, Typeface.BOLD)
        b.setPadding(dp(16), dp(10), dp(16), dp(10))
        hook {
            b.background = round(if (primary) pal.accent else pal.button, 22)
            b.setTextColor(if (primary) Color.WHITE else pal.panelText)
        }
        b.setOnClickListener { onClick() }
        return b
    }

    private fun buildUi() {
        // --- baner manewru (góra) ---
        banner = panel(LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL })
        arrow = TurnArrowView(this)
        hook { arrow.color = pal.accent }
        banner.addView(arrow, LinearLayout.LayoutParams(dp(64), dp(64)))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        bannerDist = text(28f, bold = true)
        bannerStreet = text(16f)
        bannerThen = text(13f, primary = false)
        col.addView(bannerDist); col.addView(bannerStreet); col.addView(bannerThen)
        banner.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        banner.visibility = View.GONE
        overlay.addView(banner, FrameLayout.LayoutParams(-1, -2).apply { setMargins(dp(10), dp(8), dp(10), 0) })

        statusText = text(14f, bold = true).apply {
            gravity = Gravity.CENTER; setPadding(dp(14), dp(8), dp(14), dp(8)); visibility = View.GONE
            hook { background = round(pal.panel, 16) }
        }
        overlay.addView(statusText, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(100) })

        // --- przyciski po prawej ---
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun add(b: View) = right.addView(b, LinearLayout.LayoutParams(dp(54), dp(54)).apply { topMargin = dp(10) })
        add(roundButton("🔍") { showSearch() })
        add(roundButton("☰") { showMenu() })
        add(roundButton("⚠") { showReportDialog() })
        add(roundButton("◎") { recenter() })
        overlay.addView(right, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = dp(10) })

        // --- prędkość i limit (lewy dół) ---
        speedCluster = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        streetChip = text(14f, bold = true).apply {
            setPadding(dp(10), dp(4), dp(10), dp(4)); visibility = View.GONE; maxLines = 1
            hook { background = round(pal.panel, 12) }
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        speedView = SpeedView(this)
        limitView = LimitSignView(this).apply { visibility = View.GONE }
        hook { speedView.palette = pal; limitView.palette = pal; speedView.invalidate(); limitView.invalidate() }
        row.addView(speedView, LinearLayout.LayoutParams(dp(82), dp(82)))
        row.addView(limitView, LinearLayout.LayoutParams(dp(64), dp(64)).apply { leftMargin = dp(8) })
        hazardChip = text(15f, bold = true).apply {
            setPadding(dp(10), dp(5), dp(10), dp(5)); visibility = View.GONE
            hook { background = round(if (pal.night) 0xFF5D3A00.toInt() else 0xFFFFE0B2.toInt(), 12) }
        }
        speedCluster.addView(hazardChip, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(6) })
        speedCluster.addView(row)
        speedCluster.addView(streetChip, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        overlay.addView(speedCluster, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(10), 0, 0, dp(10)) })

        // --- pasek nawigacji (dół) ---
        navBar = panel(LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL })
        navInfo = text(17f, bold = true)
        navBar.addView(navInfo, LinearLayout.LayoutParams(0, -2, 1f))
        navBar.addView(textButton("Zakończ", false) { stopNavigation() })
        navBar.visibility = View.GONE
        overlay.addView(navBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(10), 0, dp(10), dp(10)) })

        // --- karta miejsca ---
        placeSheet = panel(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL })
        placeTitle = text(20f, bold = true)
        placeSheet.addView(placeTitle)
        val pr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
        pr.addView(textButton("Trasa", true) { startPreview() })
        pr.addView(textButton("Zapisz", false) { savePlaceDialog() }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        pr.addView(textButton("✕", false) { setUiMode(UiMode.BROWSE); clearMarker() }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        placeSheet.addView(pr)
        placeSheet.visibility = View.GONE
        overlay.addView(placeSheet, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(10), 0, dp(10), dp(10)) })

        // --- podgląd trasy ---
        previewSheet = panel(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL })
        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        hs.addView(chipRow)
        previewSheet.addView(hs)
        previewInfo = text(22f, bold = true).apply { setPadding(0, dp(10), 0, dp(4)) }
        previewSheet.addView(previewInfo)
        val br = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
        br.addView(textButton("Start", true) { if (previewRoute != null) startNavigation() })
        br.addView(textButton("Zapisz trasę", false) { saveRouteDialog() }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        br.addView(textButton("Test", false) { if (previewRoute != null) startNavigation(simulate = true) }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        br.addView(textButton("✕", false) { setUiMode(UiMode.BROWSE); previewRoute = null; setRouteGeometry(null); clearMarker() }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        previewSheet.addView(br)
        previewSheet.visibility = View.GONE
        overlay.addView(previewSheet, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(10), 0, dp(10), dp(10)) })
        refreshChips()
    }

    private fun refreshChips() {
        chipRow.removeAllViews()
        for (p in Profile.values()) {
            val sel = p == previewProfile
            val b = TextView(this)
            b.text = "${p.icon} ${p.label}"
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            b.setPadding(dp(14), dp(8), dp(14), dp(8))
            b.background = round(if (sel) pal.accent else pal.button, 20)
            b.setTextColor(if (sel) Color.WHITE else pal.panelText)
            b.setOnClickListener { startPreview(p) }
            chipRow.addView(b, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
        }
    }

    private fun setUiMode(m: UiMode) {
        uiMode = m
        banner.visibility = if (m == UiMode.NAV) View.VISIBLE else View.GONE
        navBar.visibility = if (m == UiMode.NAV) View.VISIBLE else View.GONE
        placeSheet.visibility = if (m == UiMode.PLACE) View.VISIBLE else View.GONE
        previewSheet.visibility = if (m == UiMode.PREVIEW) View.VISIBLE else View.GONE
        // przesuń licznik ponad dolny panel
        val lp = speedCluster.layoutParams as FrameLayout.LayoutParams
        lp.bottomMargin = dp(10) + when (m) {
            UiMode.NAV -> dp(78)
            UiMode.PLACE -> dp(0)
            UiMode.PREVIEW -> dp(0)
            else -> 0
        }
        speedCluster.visibility = if (m == UiMode.PLACE || m == UiMode.PREVIEW) View.GONE else View.VISIBLE
        speedCluster.layoutParams = lp
        if (m == UiMode.NAV) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (m == UiMode.PREVIEW) refreshChips()
    }

    private fun recenter() {
        follow = true
        val g = lastGuidance
        if (g != null) followCamera(g)
        else lastLat?.let { la -> lastLon?.let { lo -> map?.easeCamera(CameraUpdateFactory.newLatLngZoom(LatLng(la, lo), 15.0), 600) } }
    }

    // =====================================================================
    //  okna dialogowe
    // =====================================================================

    private fun showMenu() {
        val items = listOf(
            "Mapy offline (pobierz / usuń)",
            "Zapisane miejsca",
            "Zapisane trasy",
            "Motyw: ${ThemeManager.mode(this).label}",
            "Głos: ${if (voiceOn) "włączony" else "wyłączony"}",
            "Ustaw dom / pracę",
            "Głos asystentki (wybór, ton)",
            "Adres serwera map (zaawansowane)",
        )
        AlertDialog.Builder(this, dialogTheme()).setTitle("Menu").setItems(items.toTypedArray()) { _, i ->
            when (i) {
                0 -> showDownloads()
                1 -> showSavedPlaces()
                2 -> showSavedRoutes()
                3 -> showThemeChooser()
                4 -> {
                    voiceOn = !voiceOn
                    getSharedPreferences("settings", 0).edit().putBoolean("voice", voiceOn).apply()
                    toast(if (voiceOn) "Głos włączony" else "Głos wyłączony")
                }
                5 -> showHomeWork()
                6 -> showVoiceDialog()
                7 -> showDataUrlDialog()
            }
        }.show()
    }

    private fun showThemeChooser() {
        val modes = ThemeMode.values()
        AlertDialog.Builder(this, dialogTheme()).setTitle("Motyw")
            .setSingleChoiceItems(modes.map { it.label }.toTypedArray(), modes.indexOf(ThemeManager.mode(this))) { d, i ->
                ThemeManager.setMode(this, modes[i])
                d.dismiss()
                checkTheme()
            }.show()
    }

    private fun showHomeWork() {
        AlertDialog.Builder(this, dialogTheme()).setTitle("Ustaw adres")
            .setItems(arrayOf("Dom", "Praca")) { _, i ->
                pickPoint("Wyszukaj adres: " + if (i == 0) "dom" else "praca") { name, lat, lon ->
                    storage.addPlace(if (i == 0) "Dom" else "Praca", lat, lon, if (i == 0) "home" else "work")
                    refreshSaved(); toast("Zapisano: $name")
                }
            }.show()
    }

    private fun showSavedPlaces() {
        val list = storage.places
        if (list.isEmpty()) { toast("Brak zapisanych miejsc. Przytrzymaj punkt na mapie, aby go zapisać."); return }
        val names = list.map { (if (it.kind == "home") "🏠 " else if (it.kind == "work") "💼 " else "⭐ ") + it.name }
        AlertDialog.Builder(this, dialogTheme()).setTitle("Zapisane miejsca")
            .setItems(names.toTypedArray()) { _, i -> selectPlace(list[i].name, list[i].lat, list[i].lon) }
            .setNeutralButton("Usuń…") { _, _ ->
                AlertDialog.Builder(this, dialogTheme()).setTitle("Usuń miejsce")
                    .setItems(names.toTypedArray()) { _, j -> storage.removePlace(list[j].id); refreshSaved() }.show()
            }.show()
    }

    private fun showSavedRoutes() {
        val list = storage.routes
        if (list.isEmpty()) { toast("Brak zapisanych tras. Wyznacz trasę i wybierz „Zapisz trasę”."); return }
        val names = list.map { "${it.profile.icon} ${it.name}" }
        AlertDialog.Builder(this, dialogTheme()).setTitle("Zapisane trasy")
            .setItems(names.toTypedArray()) { _, i ->
                val r = list[i]
                val dest = r.points.last()
                placeName = dest.name; placeLat = dest.lat; placeLon = dest.lon
                style?.getSourceAs<GeoJsonSource>("dest-src")?.setGeoJson(Point.fromLngLat(dest.lon, dest.lat))
                startPreview(r.profile)
            }
            .setNeutralButton("Usuń…") { _, _ ->
                AlertDialog.Builder(this, dialogTheme()).setTitle("Usuń trasę")
                    .setItems(names.toTypedArray()) { _, j -> storage.removeRoute(list[j].id) }.show()
            }.show()
    }

    private fun savePlaceDialog() {
        val et = EditText(this).apply { setText(placeName); setSingleLine(); setSelection(text.length) }
        AlertDialog.Builder(this, dialogTheme()).setTitle("Zapisz miejsce").setView(et)
            .setPositiveButton("Ulubione") { _, _ -> storage.addPlace(et.text.toString().ifBlank { placeName }, placeLat, placeLon); refreshSaved(); toast("Zapisano") }
            .setNeutralButton("Jako dom") { _, _ -> storage.addPlace("Dom", placeLat, placeLon, "home"); refreshSaved(); toast("Zapisano jako dom") }
            .setNegativeButton("Jako pracę") { _, _ -> storage.addPlace("Praca", placeLat, placeLon, "work"); refreshSaved(); toast("Zapisano jako pracę") }
            .show()
    }

    private fun saveRouteDialog() {
        val et = EditText(this).apply { setText("do: $placeName"); setSingleLine(); setSelection(text.length) }
        AlertDialog.Builder(this, dialogTheme()).setTitle("Zapisz trasę").setView(et)
            .setPositiveButton("Zapisz") { _, _ ->
                val start = RoutePoint("Start", lastLat ?: 0.0, lastLon ?: 0.0)
                storage.addRoute(et.text.toString().ifBlank { "Trasa" }, previewProfile,
                    listOf(start, RoutePoint(placeName, placeLat, placeLon)))
                toast("Trasa zapisana")
            }.setNegativeButton("Anuluj", null).show()
    }

    // --- wyszukiwanie ---

    private fun showSearch() = pickPoint("Dokąd jedziemy?") { name, lat, lon -> selectPlace(name, lat, lon) }

    private fun pickPoint(title: String, onPick: (String, Double, Double) -> Unit) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0) }
        val et = EditText(this).apply {
            hint = "Miasto, ulica, współrzędne…"; setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT
        }
        val lv = ListView(this)
        box.addView(et); box.addView(lv, LinearLayout.LayoutParams(-1, dp(360)))

        class Row(val title: String, val sub: String, val lat: Double, val lon: Double)
        val rows = ArrayList<Row>()
        val adapter = object : ArrayAdapter<Row>(this, android.R.layout.simple_list_item_2, rows) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = convertView ?: layoutInflater.inflate(android.R.layout.simple_list_item_2, parent, false)
                val r = getItem(position)!!
                v.findViewById<TextView>(android.R.id.text1).text = r.title
                v.findViewById<TextView>(android.R.id.text2).text = r.sub
                return v
            }
        }
        lv.adapter = adapter

        fun fill(list: List<Row>) { rows.clear(); rows.addAll(list); adapter.notifyDataSetChanged() }
        fun defaults(): List<Row> {
            val out = ArrayList<Row>()
            storage.home()?.let { out.add(Row("🏠 Dom", it.name, it.lat, it.lon)) }
            storage.work()?.let { out.add(Row("💼 Praca", it.name, it.lat, it.lon)) }
            storage.places.filter { it.kind == "fav" }.forEach { out.add(Row("⭐ " + it.name, "zapisane", it.lat, it.lon)) }
            storage.recent.forEach { out.add(Row(it.name, "ostatnio", it.lat, it.lon)) }
            return out
        }
        fill(defaults())

        val dlg = AlertDialog.Builder(this, dialogTheme()).setTitle(title).setView(box)
            .setNegativeButton("Zamknij", null).create()
        lv.setOnItemClickListener { _, _, pos, _ ->
            val r = rows[pos]; dlg.dismiss(); onPick(r.title.removePrefix("🏠 ").removePrefix("💼 ").removePrefix("⭐ "), r.lat, r.lon)
        }
        var pending: Runnable? = null
        et.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString()?.trim().orEmpty()
                pending?.let { main.removeCallbacks(it) }
                if (q.length < 2) { fill(defaults()); return }
                val coords = parseCoords(q)
                if (coords != null) { fill(listOf(Row("Współrzędne", q, coords.first, coords.second))); return }
                pending = Runnable {
                    bg.execute {
                        val hits = searchIndex.search(q, 40, lastLat, lastLon)
                        main.post { fill(hits.map { Row(it.title, it.kind, it.lat, it.lon) }) }
                    }
                }
                main.postDelayed(pending!!, 150)
            }
        })
        dlg.show()
        if (!searchIndex.ready) toast("Wyszukiwarka wymaga pobranej mapy (menu → Mapy offline)")
    }

    private fun parseCoords(q: String): Pair<Double, Double>? {
        val m = Regex("^\\s*(-?\\d{1,3}(?:[.,]\\d+)?)\\s*[;, ]\\s*(-?\\d{1,3}(?:[.,]\\d+)?)\\s*$").find(q) ?: return null
        val a = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val b = m.groupValues[2].replace(',', '.').toDoubleOrNull() ?: return null
        return if (a in -90.0..90.0 && b in -180.0..180.0) a to b else null
    }

    // --- pobieranie map ---

    private fun showDownloads() {
        val loading = AlertDialog.Builder(this, dialogTheme()).setTitle("Mapy offline").setMessage("Pobieranie listy…").create()
        loading.show()
        bg.execute {
            val res = runCatching { dataManager.fetchIndex() }
            main.post {
                loading.dismiss()
                res.onFailure {
                    val inst = dataManager.installed()
                    if (dataManager.baseUrl.isEmpty())
                        toast("Aplikacja nie ma ustawionego adresu danych (DATA_URL). Zobacz README.")
                    else toast("Brak połączenia z serwerem map (${it.message})")
                    if (inst.isNotEmpty()) showInstalledOnly(inst)
                }
                res.onSuccess { showPackList(it) }
            }
        }
    }

    private fun showInstalledOnly(inst: List<InstalledPack>) {
        AlertDialog.Builder(this, dialogTheme()).setTitle("Zainstalowane mapy")
            .setItems(inst.map { it.name }.toTypedArray()) { _, i -> confirmDelete(inst[i].id, inst[i].name) }
            .setNegativeButton("Zamknij", null).show()
    }

    private fun showPackList(packs: List<Pack>) {
        val labels = packs.map {
            val mb = it.totalBytes / 1_000_000
            val tag = if (dataManager.isInstalled(it)) "  ✓ zainstalowano" else ""
            "${it.name}  (${if (mb >= 1000) String.format(Locale("pl"), "%.1f GB", mb / 1000.0) else "$mb MB"})$tag" +
                if (it.kind == "routing") "\nTylko trasy i wyszukiwanie – dla podróży między województwami" else ""
        }
        AlertDialog.Builder(this, dialogTheme()).setTitle("Mapy offline")
            .setItems(labels.toTypedArray()) { _, i ->
                val p = packs[i]
                if (dataManager.isInstalled(p)) confirmDelete(p.id, p.name) else startDownload(p)
            }.setNegativeButton("Zamknij", null).show()
    }

    private fun confirmDelete(id: String, name: String) {
        AlertDialog.Builder(this, dialogTheme()).setTitle(name).setMessage("Usunąć pobrane dane tego regionu?")
            .setPositiveButton("Usuń") { _, _ ->
                engine.forgetGraph(id); tileServer.unregister("$id.pmtiles")
                dataManager.delete(id)
                if (navigator.graph != null) navigator.graph = null
                reloadPacks()
            }.setNegativeButton("Anuluj", null).show()
    }

    private fun startDownload(p: Pack) {
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        val label = TextView(this).apply { text = "Przygotowanie…"; setPadding(0, dp(8), 0, 0) }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), 0) }
        box.addView(bar); box.addView(label)
        val dlg = AlertDialog.Builder(this, dialogTheme()).setTitle("Pobieranie: ${p.name}").setView(box)
            .setCancelable(false).setNegativeButton("Anuluj") { _, _ -> dataManager.cancelled = true }.create()
        dlg.show()
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        bg.execute {
            val r = runCatching {
                dataManager.download(p) { done, total ->
                    main.post {
                        bar.progress = if (total > 0) (done * 1000 / total).toInt() else 0
                        label.text = "${done / 1_000_000} / ${total / 1_000_000} MB"
                    }
                }
            }
            main.post {
                dlg.dismiss()
                if (uiMode != UiMode.NAV) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                r.onSuccess { toast("Pobrano: ${p.name}"); reloadPacks() }
                r.onFailure { toast(if (it is InterruptedException) "Anulowano (można wznowić)" else "Błąd pobierania: ${it.message}") }
            }
        }
    }

    // =====================================================================
    //  formatowanie
    // =====================================================================

    private fun formatDist(m: Double): String = when {
        m >= 10000 -> "${(m / 1000).toInt()} km"
        m >= 1000 -> String.format(Locale("pl"), "%.1f km", m / 1000)
        m >= 100 -> "${(m / 10).toInt() * 10} m"
        else -> "${m.toInt()} m"
    }

    private fun formatDuration(s: Double): String {
        val min = (s / 60).toInt().coerceAtLeast(1)
        return if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
