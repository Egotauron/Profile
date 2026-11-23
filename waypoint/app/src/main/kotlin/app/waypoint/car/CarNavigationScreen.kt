package app.waypoint.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import app.waypoint.R
import app.waypoint.graph
import app.waypoint.session.SegmentUi
import app.waypoint.session.SessionState
import app.waypoint.trip.StopWait
import app.waypoint.trip.TripPhase
import app.waypoint.ui.Format
import app.waypoint.ui.MapStyles
import app.waypoint.ui.TripMap
import com.rallista.car.app.compose.ComposableScreen
import com.stadiamaps.ferrostar.car.app.navigation.NavigationManagerBridge
import com.stadiamaps.ferrostar.car.app.template.models.FerrostarRoutingInfo
import com.stadiamaps.ferrostar.car.app.template.models.toCarTravelEstimate
import com.stadiamaps.ferrostar.core.extensions.progress
import com.stadiamaps.ferrostar.maplibreui.runtime.NavigationMapState
import com.stadiamaps.ferrostar.maplibreui.runtime.rememberNavigationMapState
import com.stadiamaps.ferrostar.ui.maplibre.car.app.CarAppNavigationView
import com.stadiamaps.ferrostar.ui.maplibre.car.app.runtime.SurfaceAreaTracker
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import uniffi.ferrostar.TripState

/**
 * Android Auto navigation (spec §5, §6): Ferrostar's map and NavigationTemplate with
 * "Stop 2 of 5", ETA to the next stop, and an action strip of at most four buttons:
 * Start next leg / Skip (at a stop) · play/pause · next track · ⋯ (stop list, End trip).
 */
class CarNavigationScreen(carContext: CarContext) : ComposableScreen(carContext, surfaceTag = "waypoint-nav") {
    private val graph = carContext.graph
    private val session = graph.session
    private var mapState: NavigationMapState? = null
    private val surfaceTracker = SurfaceAreaTracker { surfaceGestureCallback = it }
    private val bridge = NavigationManagerBridge(
        navigationManager = carContext.getCarService(NavigationManager::class.java),
        context = carContext,
        viewModel = session.navigationViewModel,
        onStopNavigation = { session.endTrip() },
        isCarForeground = { true },
    )

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                bridge.start(lifecycleScope)
                @OptIn(kotlinx.coroutines.FlowPreview::class)
                lifecycleScope.launch {
                    combine(session.state, session.navigationViewModel.navigationUiState, graph.music.nowPlaying) { a, _, _ -> a }
                        .sample(1_000)
                        .collect { s ->
                            if (screenManager.top !== this@CarNavigationScreen) {
                                // Not on screen (stop list on top, or already left): nothing to redraw.
                            } else if (s.plan == null) {
                                // Trip ended: back to the trip list.
                                if (screenManager.stackSize > 1) screenManager.pop() else screenManager.push(TripPickerScreen(carContext))
                            } else {
                                invalidate()
                            }
                        }
                }
            }

            override fun onDestroy(owner: LifecycleOwner) {
                bridge.stop()
            }
        })
    }

    @Composable
    override fun content() {
        val s by session.state.collectAsState()
        val dark = carContext.isDarkMode
        if (s.segment is SegmentUi.Guiding && s.atStop == null) {
            val state = rememberNavigationMapState()
            SideEffect { mapState = state }
            CarAppNavigationView(
                modifier = Modifier.fillMaxSize(),
                styleUrl = if (dark) MapStyles.NIGHT else MapStyles.DAY,
                navigationMapState = state,
                viewModel = session.navigationViewModel,
                surfaceAreaTracker = surfaceTracker,
            )
        } else {
            SideEffect { mapState = null }
            val focus = s.legIndex ?: s.atStop?.let { it.stopIndex + 1 }
            TripMap(Modifier.fillMaxSize(), s.plan?.trip, s.plan, s.plan?.start, dark, user = s.userLocation, focusLeg = focus)
        }
    }

    override fun onGetTemplate(): Template {
        val s = session.state.value
        val nav = session.navigationViewModel.navigationUiState.value
        val builder = NavigationTemplate.Builder()
            .setActionStrip(actionStrip(s))
            .setMapActionStrip(mapActionStrip())

        val at = s.atStop
        when {
            at != null -> builder.setNavigationInfo(MessageInfo.Builder(arrivalText(s)).setText(arrivalDetail(s)).build())
            s.segment is SegmentUi.Guiding -> {
                nav.tripState?.let { ts ->
                    FerrostarRoutingInfo.Builder(carContext).setTripState(ts).build()?.let { builder.setNavigationInfo(it) }
                    ts.progress()?.let { builder.setDestinationTravelEstimate(it.toCarTravelEstimate()) }
                }
            }
            else -> builder.setNavigationInfo(MessageInfo.Builder(otherText(s)).setText("Continue on your phone").build())
        }
        if (s.phase is TripPhase.Finished) {
            builder.setNavigationInfo(MessageInfo.Builder("Trip complete").build())
        }
        return builder.build()
    }

    private fun arrivalText(s: SessionState): String = "Stop ${s.stopNumber} of ${s.stopCount}: arrived at ${s.stopName}"

    private fun arrivalDetail(s: SessionState): String {
        val w = s.atStop?.wait
        return when {
            s.nextStopName == null -> "Trip complete"
            w is StopWait.Countdown -> "Continuing to ${s.nextStopName} in ${Format.countdown(s.countdownLeft ?: kotlin.time.Duration.ZERO)}"
            else -> "Next: ${s.nextStopName}"
        }
    }

    private fun otherText(s: SessionState): String = when (val seg = s.segment) {
        is SegmentUi.TransitWait -> "Take the ${seg.ride.line} from ${seg.ride.board.name}"
        is SegmentUi.TransitRide -> "Ride to ${seg.ride.alight.name}"
        is SegmentUi.BikePickup -> "Unlock a bike at ${seg.dock.name}"
        is SegmentUi.BikeDropoff -> "Dock the bike at ${seg.dock.name}"
        is SegmentUi.Preparing -> seg.text
        else -> "Stop ${s.stopNumber} of ${s.stopCount}: ${s.stopName}"
    }

    private fun icon(res: Int) = CarIcon.Builder(IconCompat.createWithResource(carContext, res)).build()

    private fun actionStrip(s: SessionState): ActionStrip {
        val strip = ActionStrip.Builder()
        val at = s.atStop
        if (at != null && s.nextStopName != null) {
            strip.addAction(Action.Builder().setTitle("Start next leg").setOnClickListener { session.startNextLeg() }.build())
        } else if (s.segment is SegmentUi.Guiding) {
            strip.addAction(Action.Builder().setTitle("Stop ${s.stopNumber}/${s.stopCount}").setOnClickListener { screenManager.push(CarStopListScreen(carContext)) }.build())
        }
        val playing = graph.music.nowPlaying.value?.isPlaying == true
        strip.addAction(
            Action.Builder()
                .setIcon(icon(if (playing) R.drawable.ic_car_pause else R.drawable.ic_car_play))
                .setOnClickListener { graph.music.playPause() }
                .build(),
        )
        strip.addAction(Action.Builder().setIcon(icon(R.drawable.ic_car_next)).setOnClickListener { graph.music.next() }.build())
        strip.addAction(Action.Builder().setIcon(icon(R.drawable.ic_car_more)).setOnClickListener { screenManager.push(CarStopListScreen(carContext)) }.build())
        return strip.build()
    }

    private fun mapActionStrip(): ActionStrip = ActionStrip.Builder()
        .addAction(Action.PAN)
        .addAction(Action.Builder().setIcon(icon(R.drawable.ic_car_recenter)).setOnClickListener { mapState?.recenter(isNavigating = true) }.build())
        .addAction(Action.Builder().setIcon(icon(R.drawable.ic_car_zoom_in)).setOnClickListener { mapState?.zoomIn() }.build())
        .addAction(Action.Builder().setIcon(icon(R.drawable.ic_car_zoom_out)).setOnClickListener { mapState?.zoomOut() }.build())
        .build()
}

/** ⋯ on the car: the stop list, Skip next stop, End trip. */
class CarStopListScreen(carContext: CarContext) : Screen(carContext) {
    private val session = carContext.graph.session

    override fun onGetTemplate(): Template {
        val s = session.state.value
        val list = ItemList.Builder()
        s.plan?.trip?.stops?.forEachIndexed { i, stop ->
            val leg = s.plan.legs.getOrNull(i)
            list.addItem(
                Row.Builder()
                    .setTitle("${i + 1}. ${stop.place.label}" + if (i + 1 == s.stopNumber) " (current)" else "")
                    .addText(leg?.let { "Arrive ${Format.clock(it.arriveAt)}" } ?: "")
                    .build(),
            )
        }
        val actions = ActionStrip.Builder()
        if (s.atStop != null && s.plan != null && s.plan.legs.size > s.atStop!!.stopIndex + 2) {
            actions.addAction(Action.Builder().setTitle("Skip next").setOnClickListener { session.skipNextStop(); screenManager.pop() }.build())
        }
        actions.addAction(Action.Builder().setTitle("End trip").setOnClickListener { session.endTrip(); screenManager.popToRoot() }.build())
        return ListTemplate.Builder()
            .setTitle("Stops")
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .setActionStrip(actions.build())
            .build()
    }
}
