package app.waypoint.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import app.waypoint.BuildConfig
import app.waypoint.graph
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

/**
 * Android Auto entry point (Navigation category, spec §2.2).
 *
 * The service has to be exported for the car to reach it, so the host check is what keeps other
 * apps on the phone from posing as Android Auto and reading your trips and route. Release builds
 * accept only the signed Android Auto and Android Automotive hosts listed by the Car App Library
 * (the Desktop Head Unit connects through Android Auto, so it still works). Debug builds, which
 * have their own `.dev` app ID, accept any host for testing.
 */
class WaypointCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator =
        if (BuildConfig.DEBUG) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }

    override fun onCreateSession(): Session = WaypointCarSession()
}

/**
 * The car is a view of the same [app.waypoint.session.TripSession] as the phone: when a trip
 * runs, the car opens straight on navigation (phone → car handoff, spec §4.5).
 */
class WaypointCarSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        val session = carContext.graph.session
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                lifecycleScope.launch {
                    session.state.distinctUntilChangedBy { it.active }.collect { s ->
                        val screens = carContext.getCarService(ScreenManager::class.java)
                        if (s.active && screens.top !is CarNavigationScreen) {
                            screens.popToRoot()
                            screens.push(CarNavigationScreen(carContext))
                        }
                    }
                }
            }
        })
        return if (session.state.value.active) CarNavigationScreen(carContext) else TripPickerScreen(carContext)
    }
}
