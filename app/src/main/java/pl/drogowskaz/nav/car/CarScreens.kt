package pl.drogowskaz.nav.car

import android.Manifest
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.DateTimeWithZone
import androidx.car.app.model.Distance
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import pl.drogowskaz.nav.Engine
import pl.drogowskaz.nav.Guidance
import pl.drogowskaz.nav.NavListener
import pl.drogowskaz.nav.PlaceHit
import pl.drogowskaz.nav.Profile
import pl.drogowskaz.nav.Route
import pl.drogowskaz.nav.Turn
import java.util.TimeZone

private fun fmtDist(m: Double): String = when {
    m >= 10000 -> "${(m / 1000).toInt()} km"
    m >= 1000 -> String.format(java.util.Locale("pl"), "%.1f km", m / 1000)
    else -> "${(m / 10).toInt() * 10} m"
}

private fun fmtTime(s: Double): String {
    val min = (s / 60).toInt().coerceAtLeast(1)
    return if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"
}

private fun maneuverType(t: Turn): Int = when (t) {
    Turn.START -> Maneuver.TYPE_DEPART
    Turn.STRAIGHT -> Maneuver.TYPE_STRAIGHT
    Turn.SLIGHT_LEFT -> Maneuver.TYPE_TURN_SLIGHT_LEFT
    Turn.LEFT -> Maneuver.TYPE_TURN_NORMAL_LEFT
    Turn.SHARP_LEFT -> Maneuver.TYPE_TURN_SHARP_LEFT
    Turn.SLIGHT_RIGHT -> Maneuver.TYPE_TURN_SLIGHT_RIGHT
    Turn.RIGHT -> Maneuver.TYPE_TURN_NORMAL_RIGHT
    Turn.SHARP_RIGHT -> Maneuver.TYPE_TURN_SHARP_RIGHT
    Turn.UTURN -> Maneuver.TYPE_U_TURN_LEFT
    Turn.ROUNDABOUT -> Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW   // ruch prawostronny
    Turn.KEEP_LEFT -> Maneuver.TYPE_KEEP_LEFT
    Turn.KEEP_RIGHT -> Maneuver.TYPE_KEEP_RIGHT
    Turn.ARRIVE -> Maneuver.TYPE_DESTINATION
}

private fun carDistance(m: Double): Distance =
    if (m >= 1000) Distance.create(m / 1000.0, Distance.UNIT_KILOMETERS_P1)
    else Distance.create(((m / 10).toInt() * 10).coerceAtLeast(10).toDouble(), Distance.UNIT_METERS)

/** Ekran główny: mapa + prowadzenie (gdy trwa nawigacja) albo skróty (szukaj, dom, praca, zapisane). */
class MainCarScreen(ctx: CarContext, private val engine: Engine) : Screen(ctx), NavListener {

    var navigationManager: NavigationManager? = null
    private var lastInvalidate = 0L
    private var lastManeuverText = ""
    private var wasNavigating = false

    init {
        engine.addListener(this)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) { engine.removeListener(this@MainCarScreen) }
        })
    }

    override fun onGetTemplate(): Template {
        if (!engine.hasLocationPermission()) {
            return MessageTemplate.Builder("Aplikacja potrzebuje zgody na lokalizację.")
                .setTitle("Drogowskaz").setHeaderAction(Action.APP_ICON)
                .addAction(Action.Builder().setTitle("Zezwól").setOnClickListener {
                    carContext.requestPermissions(listOf(Manifest.permission.ACCESS_FINE_LOCATION)) { granted, _ ->
                        if (granted.isNotEmpty()) engine.startLocation()
                        invalidate()
                    }
                }.build()).build()
        }

        val nav = engine.navigator.navigating
        val g = engine.lastGuidance
        val strip = ActionStrip.Builder()
        if (nav) {
            strip.addAction(Action.Builder().setTitle("Zakończ").setOnClickListener {
                engine.navigator.stop()
            }.build())
            strip.addAction(Action.Builder().setIcon(CarIcons.warn).setOnClickListener {
                screenManager.push(ReportCarScreen(carContext, engine))
            }.build())
        } else {
            strip.addAction(Action.Builder().setIcon(CarIcons.search).setOnClickListener {
                screenManager.push(SearchCarScreen(carContext, engine))
            }.build())
            engine.storage.home()?.let { h ->
                strip.addAction(Action.Builder().setIcon(CarIcons.home).setOnClickListener {
                    screenManager.push(RouteCarScreen(carContext, engine, h.name, h.lat, h.lon))
                }.build())
            }
            engine.storage.work()?.let { w ->
                strip.addAction(Action.Builder().setIcon(CarIcons.work).setOnClickListener {
                    screenManager.push(RouteCarScreen(carContext, engine, w.name, w.lat, w.lon))
                }.build())
            }
            strip.addAction(Action.Builder().setIcon(CarIcons.star).setOnClickListener {
                screenManager.push(PlacesCarScreen(carContext, engine))
            }.build())
        }

        val b = NavigationTemplate.Builder().setActionStrip(strip.build())
        val next = g?.next
        if (nav && g != null && next != null) {
            b.setNavigationInfo(routingInfo(g))
            val eta = System.currentTimeMillis() + (g.remainingSeconds * 1000).toLong()
            val tz = TimeZone.getDefault()
            b.setDestinationTravelEstimate(
                TravelEstimate.Builder(carDistance(g.remainingMeters),
                    DateTimeWithZone.create(eta, tz.getOffset(eta) / 1000, tz.getDisplayName(false, TimeZone.SHORT)))
                    .setRemainingTimeSeconds(g.remainingSeconds.toLong().coerceAtLeast(1)).build()
            )
        }
        return b.build()
    }

    private fun routingInfo(g: Guidance): RoutingInfo {
        val n = g.next!!
        fun step(m: pl.drogowskaz.nav.Maneuver): Step {
            val mb = Maneuver.Builder(maneuverType(m.turn)).setIcon(CarIcons.turn(carContext, m.turn, m.roundaboutExit))
            if (m.turn == Turn.ROUNDABOUT) mb.setRoundaboutExitNumber(m.roundaboutExit.coerceAtLeast(1))
            val sb = Step.Builder(m.text()).setManeuver(mb.build())
            if (!m.street.isNullOrBlank()) sb.setRoad(m.street)
            return sb.build()
        }
        val ri = RoutingInfo.Builder().setCurrentStep(step(n), carDistance(g.nextDistance.coerceAtLeast(0.0)))
        g.thenManeuver?.let { ri.setNextStep(step(it)) }
        return ri.build()
    }

    // ---------- NavListener ----------

    override fun onGuidance(g: Guidance) {
        val now = System.currentTimeMillis()
        val txt = g.next?.text() ?: ""
        if (txt != lastManeuverText || now - lastInvalidate > 2000) {
            lastManeuverText = txt
            lastInvalidate = now
            invalidate()
        }
    }

    override fun onRouteChanged(route: Route?) {
        val nowNav = engine.navigator.navigating
        if (nowNav && !wasNavigating) navigationManager?.navigationStarted()
        if (!nowNav && wasNavigating) navigationManager?.navigationEnded()
        wasNavigating = nowNav
        invalidate()
    }

    override fun onSpeak(text: String) {}
    override fun onArrived() { navigationManager?.navigationEnded(); wasNavigating = false; invalidate() }
    override fun onStatus(msg: String) {}
}

/** Wyszukiwanie miejsca (klawiatura dostępna, gdy auto stoi). */
class SearchCarScreen(ctx: CarContext, private val engine: Engine) : Screen(ctx) {
    private var results: List<PlaceHit> = emptyList()

    private val callback = object : SearchTemplate.SearchCallback {
        override fun onSearchTextChanged(searchText: String) { search(searchText) }
        override fun onSearchSubmitted(searchText: String) { search(searchText) }
    }

    private fun search(q: String) {
        engine.bg.execute {
            val hits = engine.searchIndex.search(q, 12, engine.lastLat, engine.lastLon)
            engine.main.post { results = hits; invalidate() }
        }
    }

    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        if (results.isEmpty()) list.setNoItemsMessage("Wpisz miasto lub ulicę")
        for (hit in results.take(6)) {
            list.addItem(Row.Builder().setTitle(hit.title).addText(hit.kind).setOnClickListener {
                screenManager.push(RouteCarScreen(carContext, engine, hit.title, hit.lat, hit.lon))
            }.build())
        }
        return SearchTemplate.Builder(callback)
            .setSearchHint("Dokąd jedziemy?")
            .setShowKeyboardByDefault(true)
            .setHeaderAction(Action.BACK)
            .setItemList(list.build())
            .build()
    }
}

/** Zapisane miejsca: dom, praca, ulubione, ostatnie cele. */
class PlacesCarScreen(ctx: CarContext, private val engine: Engine) : Screen(ctx) {
    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        val all = ArrayList<Triple<String, Double, Double>>()
        engine.storage.home()?.let { all.add(Triple("🏠 Dom", it.lat, it.lon)) }
        engine.storage.work()?.let { all.add(Triple("💼 Praca", it.lat, it.lon)) }
        engine.storage.places.filter { it.kind == "fav" }.forEach { all.add(Triple("⭐ ${it.name}", it.lat, it.lon)) }
        engine.storage.recent.forEach { all.add(Triple(it.name, it.lat, it.lon)) }
        if (all.isEmpty()) list.setNoItemsMessage("Brak zapisanych miejsc. Zapisz je w aplikacji na telefonie.")
        for ((name, la, lo) in all.take(6)) {
            list.addItem(Row.Builder().setTitle(name).setOnClickListener {
                screenManager.push(RouteCarScreen(carContext, engine, name.removePrefix("🏠 ").removePrefix("💼 ").removePrefix("⭐ "), la, lo))
            }.build())
        }
        return ListTemplate.Builder().setTitle("Zapisane miejsca").setHeaderAction(Action.BACK)
            .setSingleList(list.build()).build()
    }
}

/** Wyliczenie trasy i potwierdzenie startu. */
class RouteCarScreen(
    ctx: CarContext, private val engine: Engine,
    private val name: String, private val lat: Double, private val lon: Double,
) : Screen(ctx) {
    private var profile = Profile.CAR_FASTEST
    private var route: Route? = null
    private var error: String? = null
    private var loading = true

    init { compute() }

    private fun compute() {
        loading = true; route = null; error = null
        val la = engine.lastLat; val lo = engine.lastLon
        if (la == null || lo == null) { loading = false; error = "Brak pozycji GPS"; return }
        val g1 = engine.graphFor(la, lo)
        val g2 = engine.graphFor(lat, lon)
        when {
            g1 == null -> { loading = false; error = "Brak pobranych map tras dla Twojej okolicy." }
            g2 == null || g2 !== g1 -> { loading = false; error = "Cel leży w innym regionie. Pobierz paczkę „Polska – trasy”." }
            else -> {
                if (engine.navigator.graph !== g1) engine.navigator.graph = g1
                engine.navigator.computeRoute(la, lo, lat, lon, profile) { r, err ->
                    route = r; error = err; loading = false; invalidate()
                }
            }
        }
    }

    override fun onGetTemplate(): Template {
        if (loading) return MessageTemplate.Builder("Liczenie trasy…").setTitle(name)
            .setHeaderAction(Action.BACK).setLoading(true).build()
        val r = route
        if (r == null) return MessageTemplate.Builder(error ?: "Nie znaleziono trasy").setTitle(name)
            .setHeaderAction(Action.BACK).build()
        return MessageTemplate.Builder("${fmtTime(r.duration)}  •  ${fmtDist(r.distance)}")
            .setTitle(name).setHeaderAction(Action.BACK)
            .addAction(Action.Builder().setTitle("Start").setBackgroundColor(CarColor.BLUE).setOnClickListener {
                engine.storage.addRecent(name, lat, lon)
                engine.navigator.start(r, lat, lon)
                screenManager.popToRoot()
            }.build())
            .addAction(Action.Builder()
                .setTitle(if (profile == Profile.CAR_FASTEST) "Najkrótsza" else "Najszybsza")
                .setOnClickListener {
                    profile = if (profile == Profile.CAR_FASTEST) Profile.CAR_SHORTEST else Profile.CAR_FASTEST
                    compute(); invalidate()
                }.build())
            .build()
    }
}

/** Szybkie zgłoszenie zagrożenia z samochodu. */
class ReportCarScreen(ctx: CarContext, private val engine: Engine) : Screen(ctx) {
    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        for ((kind, label) in pl.drogowskaz.nav.Hazard.REPORT_KINDS.take(5)) {
            list.addItem(Row.Builder().setTitle(label).setOnClickListener {
                engine.report(kind)
                screenManager.pop()
            }.build())
        }
        return ListTemplate.Builder().setTitle("Zgłoś w tym miejscu").setHeaderAction(Action.BACK)
            .setSingleList(list.build()).build()
    }
}
