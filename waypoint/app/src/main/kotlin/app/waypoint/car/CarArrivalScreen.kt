package app.waypoint.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import app.waypoint.graph
import app.waypoint.session.TripSession
import app.waypoint.trip.StopWait
import app.waypoint.trip.TripPhase
import app.waypoint.ui.Format
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

/**
 * Arrival card on the car screen (spec §4.2, §6 car screen 3): a message template with
 * Start now / Pause (Auto) or Start next leg / Skip next stop (Manual). It closes itself when
 * the next leg starts. The countdown shows the departure time rather than ticking, so the car
 * host isn't asked to redraw every second.
 */
class CarArrivalScreen(carContext: CarContext, private val stopIndex: Int) : Screen(carContext) {
    private val session: TripSession = carContext.graph.session

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                lifecycleScope.launch {
                    session.state.distinctUntilChangedBy { it.phase }.collect { s ->
                        val at = s.atStop
                        if (at == null || at.stopIndex != stopIndex) {
                            if (screenManager.top === this@CarArrivalScreen) screenManager.pop() else screenManager.remove(this@CarArrivalScreen)
                        } else {
                            invalidate()
                        }
                    }
                }
            }
        })
    }

    @Suppress("DEPRECATION") // setTitle / setHeaderAction: kept for hosts below Car API level 7.
    override fun onGetTemplate(): Template {
        val s = session.state.value
        val at = s.atStop
        val plan = s.plan
        val stopName = plan?.trip?.stops?.getOrNull(stopIndex)?.place?.label ?: "stop"
        val nextLeg = plan?.legs?.getOrNull(stopIndex + 1)
        val next = s.nextStopName
        val canSkip = plan != null && plan.legs.size > stopIndex + 2

        val builder = when (val wait = at?.wait) {
            is StopWait.Countdown -> MessageTemplate.Builder(
                "Continuing to $next at ${Format.clock(wait.until)}" +
                    (nextLeg?.let { " · ${it.mode.name.lowercase().replace('_', ' ')} ${Format.duration(it.duration)}" } ?: ""),
            )
                .addAction(Action.Builder().setTitle("Start now").setFlags(Action.FLAG_PRIMARY).setOnClickListener { session.startNextLeg() }.build())
                .addAction(Action.Builder().setTitle("Pause").setOnClickListener { session.pauseCountdown() }.build())
            else -> MessageTemplate.Builder(
                if (next != null) "Next: $next" + (nextLeg?.let { " · ${Format.duration(it.duration)}" } ?: "") else "Trip complete",
            ).apply {
                if (next != null) {
                    addAction(Action.Builder().setTitle("Start next leg").setFlags(Action.FLAG_PRIMARY).setOnClickListener { session.startNextLeg() }.build())
                }
                if (canSkip) addAction(Action.Builder().setTitle("Skip next stop").setOnClickListener { session.skipNextStop() }.build())
            }
        }
        return builder
            .setTitle("Arrived at $stopName")
            .setHeaderAction(Action.BACK)
            .build()
    }
}
