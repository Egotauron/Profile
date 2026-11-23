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
import app.waypoint.graph
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

/**
 * Android Auto entry point (Navigation category, spec §2.2). This is a personal build installed
 * from Android Studio, so any Android Auto host is accepted; a released app would list the hosts.
 */
class WaypointCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

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
