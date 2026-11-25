package app.waypoint.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.RoutePreviewNavigationTemplate
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import app.waypoint.graph
import app.waypoint.session.currentLocation
import app.waypoint.trip.Plan
import app.waypoint.trip.Trip
import app.waypoint.ui.Format
import app.waypoint.ui.TripMap
import com.rallista.car.app.compose.ComposableScreen
import kotlin.time.Clock
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Car screen 1 (spec §6): saved trips / current trip → route preview → Start. */
class TripPickerScreen(carContext: CarContext) : Screen(carContext) {
    private val graph = carContext.graph
    private var prepared: Plan? = graph.session.preparedPlan.value
    private var saved: List<Trip> = emptyList()

    init {
        lifecycleScope.launch {
            combine(graph.session.preparedPlan, graph.repo.savedTrips) { p, s -> p to s }.collect { (p, s) ->
                prepared = p
                saved = s
                invalidate()
            }
        }
    }

    override fun onGetTemplate(): Template {
        val items = ItemList.Builder()
        prepared?.let { plan ->
            items.addItem(
                Row.Builder()
                    .setTitle("Current trip: ${plan.trip.name ?: plan.trip.stops.last().place.label}")
                    .addText("${plan.legs.size} stops · arrive ${Format.clock(plan.arriveAt)}")
                    .setOnClickListener { screenManager.push(CarRoutePreviewScreen(carContext, plan)) }
                    .build(),
            )
        }
        saved.take(5).forEach { trip ->
            items.addItem(
                Row.Builder()
                    .setTitle(trip.name ?: "Trip")
                    .addText(trip.stops.joinToString(" → ") { it.place.label })
                    .setOnClickListener { planAndPreview(trip) }
                    .build(),
            )
        }
        if (prepared == null && saved.isEmpty()) {
            return MessageTemplate.Builder("Plan a trip on your phone, or save one, to start it here.")
                .setTitle("Waypoint")
                .setHeaderAction(Action.APP_ICON)
                .build()
        }
        return ListTemplate.Builder()
            .setTitle("Trips")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(items.build())
            .build()
    }

    private fun planAndPreview(trip: Trip) {
        screenManager.push(CarPlanningScreen(carContext))
        lifecycleScope.launch {
            val start = carContext.currentLocation()
            val result = if (start == null) Result.failure(IllegalStateException("Waiting for your location"))
            else runCatching { graph.planner().plan(trip.copy(time = app.waypoint.trip.TimeConstraint.LeaveNow), start, Clock.System.now()) }
            screenManager.pop()
            result.onSuccess {
                graph.session.preparedPlan.value = it
                screenManager.push(CarRoutePreviewScreen(carContext, it))
            }.onFailure { e ->
                screenManager.push(CarMessageScreen(carContext, e.message ?: "Couldn't plan this trip"))
            }
        }
    }
}

class CarPlanningScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template = MessageTemplate.Builder("Planning…")
        .setLoading(true)
        .setHeaderAction(Action.BACK)
        .build()
}

class CarMessageScreen(carContext: CarContext, private val text: String) : Screen(carContext) {
    override fun onGetTemplate(): Template = MessageTemplate.Builder(text).setTitle("Waypoint").setHeaderAction(Action.BACK).build()
}

/** Route preview with the trip drawn on the car map, then Start. */
class CarRoutePreviewScreen(carContext: CarContext, private val plan: Plan) : ComposableScreen(carContext) {
    @Composable
    override fun content() {
        app.waypoint.ui.MapAppearance(carContext.graph.settings.current) {
            TripMap(Modifier.fillMaxSize(), plan.trip, plan, plan.start, dark = carContext.isDarkMode)
        }
    }

    @Suppress("DEPRECATION")
    override fun onGetTemplate(): Template {
        val row = Row.Builder()
            .setTitle("${Format.clock(plan.departAt)} → ${Format.clock(plan.arriveAt)}")
            .addText("${plan.legs.size} stops · ${Format.duration(plan.arriveAt - plan.departAt)} · ETA without live traffic")
            .build()
        return RoutePreviewNavigationTemplate.Builder()
            .setTitle(plan.trip.name ?: plan.trip.stops.last().place.label)
            .setHeaderAction(Action.BACK)
            .setItemList(ItemList.Builder().addItem(row).setSelectedIndex(0).setOnSelectedListener { }.build())
            .setNavigateAction(
                Action.Builder().setTitle("Start").setOnClickListener {
                    carContext.graph.session.start(plan)
                    screenManager.popToRoot()
                    screenManager.push(CarNavigationScreen(carContext))
                }.build(),
            )
            .build()
    }
}
