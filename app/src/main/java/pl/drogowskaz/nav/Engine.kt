package pl.drogowskaz.nav

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class DrogowskazApp : Application() {
    val engine: Engine by lazy { Engine(this) }
}

/**
 * Wspólny silnik aplikacji - jeden dla ekranu telefonu i dla Android Auto:
 * GPS, dane map, nawigator, głos, zapisane miejsca i ostrzeżenia.
 */
class Engine(private val app: Application) : NavListener, LocationListener {

    val storage = Storage(app)
    val dataManager = DataManager(app)
    val tileServer = TileServer(app.assets).also { it.start() }
    val searchIndex = SearchIndex()
    val hazardIndex = HazardIndex()
    val navigator = Navigator(this)
    val bg = Executors.newSingleThreadExecutor()
    val main = Handler(Looper.getMainLooper())

    private val graphs = HashMap<String, RoadGraph>()
    private val listeners = CopyOnWriteArrayList<NavListener>()
    private val packListeners = CopyOnWriteArrayList<() -> Unit>()

    var lastLat: Double? = null
        private set
    var lastLon: Double? = null
        private set
    var lastGuidance: Guidance? = null
        private set
    var simulating = false
    var voiceOn: Boolean = app.getSharedPreferences("settings", 0).getBoolean("voice", true)
        set(v) { field = v; app.getSharedPreferences("settings", 0).edit().putBoolean("voice", v).apply() }

    var tts: TextToSpeech? = null
        private set
    var ttsReady = false
        private set

    private var locationStarted = false
    private val locMgr get() = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    init {
        navigator.hazards = hazardIndex
        hazardIndex.setUser(storage.activeReports())
        reloadPacks()
        tts = TextToSpeech(app) { st ->
            if (st == TextToSpeech.SUCCESS) {
                val r = tts?.setLanguage(Locale("pl", "PL"))
                ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                tts?.setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                )
                applyVoiceSettings()
            }
        }
        main.post(object : Runnable {
            override fun run() { navigator.tick(); main.postDelayed(this, 500) }
        })
    }

    companion object {
        fun get(ctx: Context): Engine = (ctx.applicationContext as DrogowskazApp).engine
    }

    // ---------- słuchacze ----------

    fun addListener(l: NavListener) { listeners.addIfAbsent(l) }
    fun removeListener(l: NavListener) { listeners.remove(l) }
    fun onPacksChanged(f: () -> Unit) { packListeners.add(f) }

    override fun onGuidance(g: Guidance) { lastGuidance = g; listeners.forEach { it.onGuidance(g) } }
    override fun onRouteChanged(route: Route?) {
        updateService()
        listeners.forEach { it.onRouteChanged(route) }
    }

    private var serviceOn = false

    /** Usługa pierwszoplanowa działa tylko podczas nawigacji. */
    private fun updateService() {
        val want = navigator.navigating
        if (want == serviceOn) return
        serviceOn = want
        val i = android.content.Intent(app, NavService::class.java)
        runCatching { if (want) ContextCompat.startForegroundService(app, i) else app.stopService(i) }
    }
    override fun onArrived() { simulating = false; listeners.forEach { it.onArrived() } }
    override fun onStatus(msg: String) { listeners.forEach { it.onStatus(msg) } }
    override fun onSpeak(text: String) {
        speak(text)
        listeners.forEach { it.onSpeak(text) }
    }

    // ---------- dane ----------

    /** Rejestruje pobrane mapy, ładuje wyszukiwarkę i ostrzeżenia. */
    fun reloadPacks() {
        val packs = dataManager.installed()
        for (p in packs) if (p.hasMap) tileServer.register(p.id + ".pmtiles", p.map)
        bg.execute {
            runCatching { searchIndex.load(packs.map { it.search }) }
            runCatching { hazardIndex.load(packs.map { it.warn }) }
        }
        val la = lastLat; val lo = lastLon
        if (la != null && lo != null) graphFor(la, lo)?.let { if (navigator.graph !== it) navigator.graph = it }
        packListeners.forEach { it() }
    }

    fun forgetGraph(id: String) { graphs.remove(id) }

    /** Graf dróg obejmujący punkt (preferowana paczka „Polska – trasy”). */
    fun graphFor(lat: Double, lon: Double): RoadGraph? {
        val packs = dataManager.installed().filter { it.hasRoads }.sortedBy { if (it.kind == "routing") 0 else 1 }
        for (p in packs) {
            var g = graphs[p.id]
            if (g == null) {
                g = runCatching { RoadGraph.load(p.roads) }.getOrNull() ?: continue
                graphs[p.id] = g
            }
            if (g.covers(lat, lon)) return g
        }
        return null
    }

    // ---------- GPS ----------

    fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Zwraca false, gdy brakuje zgody na lokalizację. */
    fun startLocation(): Boolean {
        if (!hasLocationPermission()) return false
        if (locationStarted) return true
        try {
            locMgr.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
            locMgr.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { lastLat = it.latitude; lastLon = it.longitude }
            locationStarted = true
        } catch (_: SecurityException) {
            return false
        } catch (_: IllegalArgumentException) {
            onStatus("Brak odbiornika GPS")
        }
        return true
    }

    override fun onLocationChanged(loc: Location) {
        if (!simulating) onLocation(loc)
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    /** Wspólne wejście dla prawdziwego GPS i symulacji. */
    fun onLocation(loc: Location) {
        lastLat = loc.latitude; lastLon = loc.longitude
        val g = navigator.graph
        if (g == null || !g.covers(loc.latitude, loc.longitude)) graphFor(loc.latitude, loc.longitude)?.let { navigator.graph = it }
        navigator.onLocation(loc)
    }

    // ---------- zgłoszenia ----------

    /** Przygotowuje graf dla trasy; zwraca komunikat błędu albo null, gdy można liczyć trasę. */
    fun prepareGraph(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): String? {
        val g1 = graphFor(fromLat, fromLon) ?: return "Brak pobranych danych tras dla Twojej okolicy."
        val g2 = graphFor(toLat, toLon)
        if (g2 == null || g2 !== g1) return "Cel leży w innym regionie. Pobierz paczkę „Polska – trasy”."
        if (navigator.graph !== g1) navigator.graph = g1
        return null
    }

    fun report(kind: String): Boolean {
        val la = lastLat ?: return false
        val lo = lastLon ?: return false
        storage.addReport(kind, la, lo)
        hazardIndex.setUser(storage.activeReports())
        return true
    }

    // ---------- głos ----------

    fun voicePrefs() = app.getSharedPreferences("settings", 0)

    fun friendlyOn() = voicePrefs().getBoolean("friendly", true)

    /** Przyjazny styl komunikatów (jeśli włączony). */
    fun friendly(t: String): String {
        if (!friendlyOn()) return t
        return when {
            t.startsWith("Rozpoczynam nawigację.") -> t.replace("Rozpoczynam nawigację.", "Cześć! Ruszamy.")
            t == "Przeliczam trasę" -> "Spokojnie, przeliczam trasę"
            t == "Dojechałeś do celu" -> "Jesteśmy na miejscu. Miłej podróży!"
            else -> t
        }
    }

    fun applyVoiceSettings() {
        val t = tts ?: return
        val pr = voicePrefs()
        val (pitch, rate) = when (pr.getString("voicePreset", "sweet")) {
            "sweet" -> 1.2f to 0.95f
            "soft" -> 1.1f to 0.88f
            "lively" -> 1.15f to 1.05f
            else -> 1.0f to 1.0f
        }
        t.setPitch(pitch)
        t.setSpeechRate(rate)
        val name = pr.getString("voiceName", null)
        if (name != null) t.voices?.firstOrNull { it.name == name }?.let { t.voice = it }
    }

    fun speak(text: String) {
        if (!voiceOn || !ttsReady) return
        tts?.speak(friendly(text), TextToSpeech.QUEUE_FLUSH, null, "nav")
    }

    fun speakSample() {
        if (!ttsReady) return
        applyVoiceSettings()
        tts?.speak(friendly("Rozpoczynam nawigację. Za dwieście metrów skręć w prawo w ulicę Słoneczną"),
            TextToSpeech.QUEUE_FLUSH, null, "sample")
    }
}
