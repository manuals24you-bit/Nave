package pl.drogowskaz.nav.car

import android.app.Presentation
import android.content.Context
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.widget.FrameLayout
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import pl.drogowskaz.nav.Engine
import pl.drogowskaz.nav.Guidance
import pl.drogowskaz.nav.LimitSignView
import pl.drogowskaz.nav.MapOverlays
import pl.drogowskaz.nav.MapStyle
import pl.drogowskaz.nav.NavListener
import pl.drogowskaz.nav.Palette
import pl.drogowskaz.nav.Route
import pl.drogowskaz.nav.SpeedView
import pl.drogowskaz.nav.ThemeManager
import android.widget.TextView
import android.view.View
import android.graphics.Color
import android.graphics.drawable.GradientDrawable

/**
 * Rysuje mapę na ekranie samochodu: Android Auto daje nam powierzchnię (Surface),
 * a MapLibre renderuje do niej przez wirtualny ekran z widokiem mapy (Presentation).
 */
class CarMapRenderer(
    private val ctx: CarContext,
    lifecycle: Lifecycle,
    private val engine: Engine,
) : SurfaceCallback, DefaultLifecycleObserver {

    private var vd: VirtualDisplay? = null
    private var presentation: CarMapPresentation? = null
    private var visibleArea: Rect? = null

    init { lifecycle.addObserver(this) }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        val surface = surfaceContainer.surface ?: return
        release()
        val dm = ctx.getSystemService(DisplayManager::class.java)
        vd = dm.createVirtualDisplay(
            "DrogowskazCarMap", surfaceContainer.width, surfaceContainer.height, surfaceContainer.dpi, surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
        )
        val display = vd?.display ?: return
        presentation = CarMapPresentation(ctx, display, engine).also {
            it.show()
            visibleArea?.let { r -> it.setVisibleArea(r) }
        }
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        this.visibleArea = visibleArea
        presentation?.setVisibleArea(visibleArea)
    }

    override fun onStableAreaChanged(stableArea: Rect) {}

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) { release() }

    override fun onScroll(distanceX: Float, distanceY: Float) { presentation?.scrollBy(distanceX, distanceY) }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) { presentation?.zoomBy(scaleFactor, focusX, focusY) }

    private fun release() {
        presentation?.let { runCatching { it.dismiss() } }
        presentation = null
        vd?.release()
        vd = null
    }

    override fun onDestroy(owner: LifecycleOwner) { release() }
}

class CarMapPresentation(outer: Context, display: Display, private val engine: Engine) :
    Presentation(outer, display), NavListener {

    private lateinit var mapView: MapView
    private lateinit var root: FrameLayout
    private lateinit var speedView: SpeedView
    private lateinit var limitView: LimitSignView
    private lateinit var hazardChip: TextView
    private lateinit var cluster: android.widget.LinearLayout
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var pal: Palette = Palette.DAY
    private var styleSig = ""
    private var lastGesture = 0L
    private val main = Handler(Looper.getMainLooper())

    private val themeTick = object : Runnable {
        override fun run() { restyle(); main.postDelayed(this, 60_000) }
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(context)
        pal = if (ThemeManager.isNight(context, engine.lastLat, engine.lastLon)) Palette.NIGHT else Palette.DAY

        root = FrameLayout(context)
        val opts = MapLibreMapOptions.createFromAttributes(context).textureMode(true)
        mapView = MapView(context, opts)
        root.addView(mapView, FrameLayout.LayoutParams(-1, -1))

        cluster = android.widget.LinearLayout(context).apply { orientation = android.widget.LinearLayout.VERTICAL }
        hazardChip = TextView(context).apply {
            setPadding(dp(10), dp(5), dp(10), dp(5)); visibility = View.GONE
            textSize = 16f
            setTextColor(Color.BLACK)
            background = GradientDrawable().apply { setColor(0xFFFFE0B2.toInt()); cornerRadius = dp(12).toFloat() }
        }
        speedView = SpeedView(context)
        limitView = LimitSignView(context)
        limitView.visibility = View.GONE
        val row = android.widget.LinearLayout(context).apply { orientation = android.widget.LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        row.addView(speedView, android.widget.LinearLayout.LayoutParams(dp(84), dp(84)))
        row.addView(limitView, android.widget.LinearLayout.LayoutParams(dp(66), dp(66)).apply { leftMargin = dp(8) })
        cluster.addView(hazardChip, android.widget.LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(6) })
        cluster.addView(row)
        root.addView(cluster, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(12), 0, 0, dp(12)) })
        applyPalette()
        setContentView(root)

        mapView.onCreate(null)
        mapView.getMapAsync { m ->
            map = m
            m.uiSettings.isAttributionEnabled = false
            m.uiSettings.isLogoEnabled = false
            m.uiSettings.isCompassEnabled = false
            m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(engine.lastLat ?: 52.0, engine.lastLon ?: 19.4), if (engine.lastLat != null) 15.0 else 5.5))
            restyle()
        }
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart(); mapView.onResume()
        engine.addListener(this)
        main.postDelayed(themeTick, 60_000)
    }

    override fun onStop() {
        main.removeCallbacksAndMessages(null)
        engine.removeListener(this)
        mapView.onPause(); mapView.onStop(); mapView.onDestroy()
        super.onStop()
    }

    private fun applyPalette() {
        speedView.palette = pal; limitView.palette = pal
        speedView.invalidate(); limitView.invalidate()
    }

    private fun restyle() {
        val m = map ?: return
        val night = ThemeManager.isNight(context, engine.lastLat, engine.lastLon)
        val ids = engine.dataManager.installed().filter { it.hasMap }.map { it.id }
        val sig = ids.joinToString(",") + "|" + night
        if (sig == styleSig) return
        styleSig = sig
        pal = if (night) Palette.NIGHT else Palette.DAY
        applyPalette()
        m.setStyle(Style.Builder().fromJson(MapStyle.build(engine.tileServer.port, ids, night))) { st ->
            style = st
            MapOverlays.install(st, pal)
            MapOverlays.setRoute(st, engine.navigator.route)
            engine.lastGuidance?.let { MapOverlays.setMe(st, it.lat, it.lon, it.bearing) }
        }
    }

    fun setVisibleArea(r: Rect) {
        val lp = cluster.layoutParams as FrameLayout.LayoutParams
        lp.leftMargin = r.left + dp(12)
        lp.bottomMargin = (root.height - r.bottom).coerceAtLeast(0) + dp(12)
        cluster.layoutParams = lp
    }

    fun scrollBy(dx: Float, dy: Float) {
        lastGesture = System.currentTimeMillis()
        val m = map ?: return
        val c = m.cameraPosition.target ?: return
        val pt = m.projection.toScreenLocation(c)
        val ll = m.projection.fromScreenLocation(android.graphics.PointF(pt.x + dx, pt.y + dy))
        m.moveCamera(CameraUpdateFactory.newLatLng(ll))
    }

    fun zoomBy(scale: Float, fx: Float, fy: Float) {
        lastGesture = System.currentTimeMillis()
        val m = map ?: return
        m.moveCamera(CameraUpdateFactory.zoomBy(Math.log(scale.toDouble()) / Math.log(2.0), android.graphics.Point(fx.toInt(), fy.toInt())))
    }

    // ---------- NavListener ----------

    override fun onGuidance(g: Guidance) {
        MapOverlays.setMe(style, g.lat, g.lon, g.bearing)
        speedView.speed = g.speedKmh
        speedView.gpsLost = g.gpsLost
        speedView.over = g.limitKmh > 0 && g.speedKmh > g.limitKmh
        speedView.invalidate()
        limitView.limit = g.limitKmh
        val hz = g.hazard
        if (hz != null) {
            hazardChip.text = "${hz.icon()} ${hz.label()} • ${g.hazardDistance.toInt() / 10 * 10} m"
            hazardChip.visibility = View.VISIBLE
        } else hazardChip.visibility = View.GONE

        val m = map ?: return
        if (System.currentTimeMillis() - lastGesture < 8000) return
        val nav = engine.navigator.navigating
        val zoom = if (nav) (17.0 - g.speedKmh / 120.0 * 2.4).coerceIn(14.4, 17.0) else 15.5
        val cam = CameraPosition.Builder().target(LatLng(g.lat, g.lon)).zoom(zoom).bearing(g.bearing)
            .tilt(if (nav) 45.0 else 0.0).padding(0.0, root.height * 0.22, 0.0, 0.0).build()
        m.easeCamera(CameraUpdateFactory.newCameraPosition(cam), 900)
    }

    override fun onRouteChanged(route: Route?) { MapOverlays.setRoute(style, route) }
    override fun onSpeak(text: String) {}
    override fun onArrived() {}
    override fun onStatus(msg: String) {}
}
