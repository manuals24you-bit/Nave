package pl.drogowskaz.nav.car

import android.content.Intent
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.validation.HostValidator
import pl.drogowskaz.nav.Engine

/** Usługa Android Auto (kategoria: nawigacja). */
class DrogowskazCarService : CarAppService() {
    // Aplikacja instalowana ręcznie (nie ze Sklepu Play) - akceptujemy każdego hosta.
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = DrogowskazSession()
}

class DrogowskazSession : Session() {
    private var renderer: CarMapRenderer? = null

    override fun onCreateScreen(intent: Intent): Screen {
        val engine = Engine.get(carContext)
        engine.startLocation()

        renderer = CarMapRenderer(carContext, lifecycle, engine)
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(renderer)

        val screen = MainCarScreen(carContext, engine)
        val nav = carContext.getCarService(NavigationManager::class.java)
        nav.setNavigationManagerCallback(object : NavigationManagerCallback {
            override fun onStopNavigation() { engine.navigator.stop() }
            override fun onAutoDriveEnabled() {}
        })
        screen.navigationManager = nav
        return screen
    }
}
