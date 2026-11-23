package app.waypoint.session

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import androidx.car.app.connection.CarConnection
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.waypoint.AppGraph
import app.waypoint.R
import app.waypoint.WaypointApp
import app.waypoint.providers.TflClient
import app.waypoint.trip.Continuation
import app.waypoint.trip.Dock
import app.waypoint.trip.Geo
import app.waypoint.trip.LatLng
import app.waypoint.trip.London
import app.waypoint.trip.NavRoute
import app.waypoint.trip.Plan
import app.waypoint.trip.RouteRequest
import app.waypoint.trip.RuntimeEffect
import app.waypoint.trip.Segment
import app.waypoint.trip.StopWait
import app.waypoint.trip.TransitVehicle
import app.waypoint.trip.TravelMode
import app.waypoint.trip.TripPhase
import app.waypoint.trip.TripRuntime
import app.waypoint.trip.VehicleState
import com.stadiamaps.ferrostar.core.AndroidTtsObserver
import com.stadiamaps.ferrostar.core.CustomRouteProvider
import com.stadiamaps.ferrostar.core.FerrostarCore
import com.stadiamaps.ferrostar.core.NavigationUiState
import com.stadiamaps.ferrostar.core.NavigationViewModel
import com.stadiamaps.ferrostar.core.extensions.fromOsrm
import com.stadiamaps.ferrostar.core.http.OkHttpClientProvider
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import uniffi.ferrostar.CourseFiltering
import uniffi.ferrostar.NavigationControllerConfig
import uniffi.ferrostar.Route
import uniffi.ferrostar.RouteAdapter
import uniffi.ferrostar.RouteDeviationTracking
import uniffi.ferrostar.SpokenInstruction
import uniffi.ferrostar.TripState
import uniffi.ferrostar.UserLocation
import uniffi.ferrostar.Waypoint
import uniffi.ferrostar.WaypointAdvanceMode
import uniffi.ferrostar.WellKnownRouteProvider
import uniffi.ferrostar.stepAdvanceDistanceEntryAndExit
import uniffi.ferrostar.stepAdvanceDistanceToEndOfStep

/** What the current part of a leg needs from you (spec §4.5, §6.5). */
sealed interface SegmentUi {
    data object Idle : SegmentUi
    data class Preparing(val text: String) : SegmentUi
    /** Turn-by-turn by Ferrostar (drive, walk, cycle). */
    data class Guiding(val mode: TravelMode, val destination: String) : SegmentUi
    data class TransitWait(val ride: Segment.TransitRide, val arrivals: List<TflClient.Arrival>) : SegmentUi
    data class TransitRide(val ride: Segment.TransitRide, val getOffNext: Boolean) : SegmentUi
    data class BikePickup(val dock: Dock) : SegmentUi
    data class BikeDropoff(val dock: Dock) : SegmentUi
}

enum class UserAction { ON_BOARD, OFF_VEHICLE, GOT_BIKE, DOCKED }

data class SessionState(
    val plan: Plan? = null,
    val phase: TripPhase = TripPhase.NotStarted,
    val segment: SegmentUi = SegmentUi.Idle,
    val segmentIndex: Int = 0,
    val countdownLeft: Duration? = null,
    val message: String? = null,
    val userLocation: LatLng? = null,
    val carConnected: Boolean = false,
) {
    val active: Boolean get() = plan != null && phase !is TripPhase.Finished && phase != TripPhase.NotStarted
    val stopCount: Int get() = plan?.legs?.size ?: 0
    val stopNumber: Int
        get() = when (val p = phase) {
            TripPhase.NotStarted -> 1
            is TripPhase.OnLeg -> p.legIndex + 1
            is TripPhase.AtStop -> p.stopIndex + 1
            is TripPhase.Finished -> stopCount
        }
    val legIndex: Int? get() = (phase as? TripPhase.OnLeg)?.legIndex
    val atStop: TripPhase.AtStop? get() = phase as? TripPhase.AtStop
    val stopName: String? get() = plan?.trip?.stops?.getOrNull(stopNumber - 1)?.place?.label
    val nextStopName: String? get() = atStop?.let { plan?.trip?.stops?.getOrNull(it.stopIndex + 1)?.place?.label }
    val nextLegMode: TravelMode? get() = atStop?.let { plan?.legs?.getOrNull(it.stopIndex + 1)?.mode }
}

/**
 * Owns the trip while it runs (spec §2.2 "Trip runtime"): the phone screens and the Android Auto
 * screen are both views of this object, kept alive by [TripSessionService].
 */
class TripSession(private val app: Application, private val graph: AppGraph) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /** The trip planned in the builder, offered to Android Auto ("current trip"). */
    val preparedPlan = MutableStateFlow<Plan?>(null)

    val location = SwitchableLocationProvider(app, graph.settings.current.simulateLocation)
    val tts = AndroidTtsObserver(app).also { it.start() }

    private var recalcAdapter: RouteAdapter? = null
    private val ferrostarHttp = OkHttpClientProvider(graph.http)

    val core: FerrostarCore = FerrostarCore(
        customRouteProvider = CustomRouteProvider { loc, wps -> recalculate(loc, wps) },
        httpClient = ferrostarHttp,
        locationProvider = location,
        navigationControllerConfig = navigationConfig(),
    ).apply { spokenInstructionObserver = tts }

    val navigationViewModel: NavigationViewModel = SessionNavigationViewModel()

    private var runtime: TripRuntime? = null
    private var legJob: Job? = null
    private var tickJob: Job? = null
    private var trackJob: Job? = null
    private val actions = Channel<UserAction>(Channel.CONFLATED)
    private var lastProgressAt: Instant = Instant.DISTANT_PAST

    init {
        CarConnection(app).type.observeForever { type ->
            val connected = type == CarConnection.CONNECTION_TYPE_PROJECTION || type == CarConnection.CONNECTION_TYPE_NATIVE
            val was = _state.value.carConnected
            _state.update { it.copy(carConnected = connected) }
            if (connected != was) onCarConnectionChanged(connected)
        }
        scope.launch {
            graph.settings.settings.collect { s ->
                location.simulate = s.simulateLocation
                tts.setMuted(!s.voiceGuidance)
            }
        }
    }

    // ---- Commands from the phone, the notification and the car -------------------------------

    fun start(plan: Plan) {
        endTrip(silently = true)
        val rt = TripRuntime(plan)
        runtime = rt
        _state.value = SessionState(plan = plan, carConnected = _state.value.carConnected)
        ContextCompat.startForegroundService(app, Intent(app, TripSessionService::class.java))
        trackJob = scope.launch {
            location.locationUpdates(2_000).collect { loc -> _state.update { it.copy(userLocation = loc.toLatLng()) } }
        }
        handle(rt.start(now()))
    }

    fun startNextLeg() = runtime?.let { handle(it.startNextLeg(now())) }

    fun skipNextStop() = runtime?.let { handle(it.skipNextStop()) }

    fun pauseCountdown() {
        runtime?.pauseCountdown()
        publish()
    }

    fun confirm(action: UserAction) {
        actions.trySend(action)
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun endTrip(silently: Boolean = false) {
        legJob?.cancel()
        tickJob?.cancel()
        trackJob?.cancel()
        core.stopNavigation()
        runtime?.end(now())
        runtime = null
        if (!silently) _state.update { SessionState(carConnected = it.carConnected) }
        app.stopService(Intent(app, TripSessionService::class.java))
    }

    // ---- Effects --------------------------------------------------------------------------------

    private fun handle(effects: List<RuntimeEffect>) {
        for (e in effects) {
            when (e) {
                is RuntimeEffect.StartLeg -> beginLeg(e.legIndex)
                is RuntimeEffect.ShowArrival -> onArrival(e.stopIndex)
                is RuntimeEffect.Retime -> scope.launch { retime(e.fromLeg, e.departAt) }
                is RuntimeEffect.SkipStop -> scope.launch { skip(e.atStopIndex, e.skippedStopId) }
                is RuntimeEffect.DriveCompleted -> graph.recordDrive(e.area, e.bucket, e.planned, e.actual)
                RuntimeEffect.Finished -> finish()
            }
        }
        publish()
    }

    private fun publish() {
        val rt = runtime ?: return
        _state.update { it.copy(plan = rt.plan, phase = rt.phase) }
    }

    private suspend fun retime(fromLeg: Int, departAt: Instant) {
        val rt = runtime ?: return
        val updated = runCatching { graph.planner().retime(rt.plan, fromLeg, departAt, now()) }
            .getOrElse { return show("Couldn't update the timetable: ${it.message}") }
        if (runtime !== rt) return
        val current = _state.value
        rt.replacePlan(updated)
        publish()
        // The leg you're on got a different transit or bike journey before you set off: restart it.
        // (Drive, walk and cycle legs are only shifted in time, so guidance carries on.)
        if (current.legIndex == fromLeg && current.segmentIndex == 0 &&
            updated.legs[fromLeg].segments != current.plan?.legs?.getOrNull(fromLeg)?.segments
        ) {
            beginLeg(fromLeg)
        }
    }

    private suspend fun skip(atStop: Int, skippedId: String) {
        val rt = runtime ?: return
        val here = _state.value.userLocation ?: rt.plan.trip.stops[atStop].place.latLng
        val updated = runCatching {
            graph.planner().replanFrom(rt.plan, atStop, here, now(), skippedId, vehicleStateAt(rt.plan, atStop))
        }.getOrElse { return show("Couldn't skip the stop: ${it.message}") }
        if (runtime !== rt) return
        rt.replacePlan(updated)
        publish()
        if (updated.legs.size == atStop + 1) finish() else show("Skipped. Next: ${updated.trip.stops[atStop + 1].place.label}")
    }

    /** Where your car and bike are after arriving at [stopIndex] (vehicle rule, §4.3). */
    private fun vehicleStateAt(plan: Plan, stopIndex: Int): VehicleState {
        fun lastAt(mode: TravelMode): Pair<LatLng?, Boolean> {
            val i = plan.legs.take(stopIndex + 1).indexOfLast { it.mode == mode }
            if (i < 0) return plan.start to false
            val stop = plan.trip.stops[i]
            val at = if (mode == TravelMode.DRIVE) stop.arrivalPoint ?: stop.place.latLng else stop.place.latLng
            return at to (i == stopIndex && !stop.leaveVehicle)
        }
        val (car, withCar) = lastAt(TravelMode.DRIVE)
        val (bike, withBike) = lastAt(TravelMode.CYCLE)
        return VehicleState(car, withCar, bike, withBike)
    }

    private fun finish() {
        legJob?.cancel()
        tickJob?.cancel()
        core.stopNavigation()
        val last = runtime?.plan?.trip?.stops?.lastOrNull()?.place?.label
        speak("You've arrived${last?.let { " at $it" } ?: ""}. Trip complete.")
        publish()
        trackJob?.cancel()
        app.stopService(Intent(app, TripSessionService::class.java))
    }

    // ---- Arrival and continuation (§4.2) --------------------------------------------------------

    private fun onArrival(stopIndex: Int) {
        val rt = runtime ?: return
        core.stopNavigation()
        _state.update { it.copy(segment = SegmentUi.Idle) }
        val stop = rt.plan.trip.stops[stopIndex]
        val next = rt.plan.trip.stops.getOrNull(stopIndex + 1)
        val wait = (rt.phase as? TripPhase.AtStop)?.wait
        if (wait is StopWait.Countdown && graph.settings.current.speakCountdowns && next != null) {
            val mins = ((wait.until - now()).inWholeSeconds + 30) / 60
            speak("Arrived at ${stop.place.label}. Continuing to ${next.place.label} in $mins minute${if (mins == 1L) "" else "s"}.")
        } else {
            speak("Arrived at ${stop.place.label}.")
        }
        tickJob?.cancel()
        tickJob = scope.launch {
            while (isActive) {
                val p = runtime?.phase as? TripPhase.AtStop ?: break
                val w = p.wait
                _state.update { it.copy(countdownLeft = (w as? StopWait.Countdown)?.let { c -> (c.until - now()).coerceAtLeast(Duration.ZERO) }) }
                runtime?.let { handle(it.onTick(now())) }
                delay(1_000)
            }
            _state.update { it.copy(countdownLeft = null) }
        }
    }

    /** Handoff between car and phone (§4.5). */
    private fun onCarConnectionChanged(connected: Boolean) {
        val rt = runtime ?: return
        val at = rt.phase as? TripPhase.AtStop ?: return
        val nextLeg = rt.plan.legs.getOrNull(at.stopIndex + 1) ?: return
        val auto = rt.plan.trip.continuationAt(rt.plan.trip.stops[at.stopIndex]) == Continuation.Auto
        if (connected && nextLeg.mode == TravelMode.DRIVE && auto && at.wait is StopWait.Countdown) {
            // Back in the car: the drive starts straight away.
            startNextLeg()
        } else if (!connected && nextLeg.mode != TravelMode.DRIVE) {
            val nextStop = rt.plan.trip.stops[at.stopIndex + 1]
            notifyAlert("Next: ${modeVerb(nextLeg.mode)} ${nextLeg.duration.inWholeMinutes} min to ${nextStop.place.label}")
        }
    }

    // ---- Legs -----------------------------------------------------------------------------------

    private fun beginLeg(index: Int) {
        legJob?.cancel()
        tickJob?.cancel()
        _state.update { it.copy(segmentIndex = 0, countdownLeft = null) }
        legJob = scope.launch {
            try {
                runLeg(index)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                show(e.message ?: "Something went wrong on this leg")
            }
        }
    }

    private suspend fun runLeg(index: Int) {
        val rt = runtime ?: return
        var k = 0
        while (true) {
            val leg = rt.plan.legs[index]
            val seg = leg.segments.getOrNull(k) ?: break
            _state.update { it.copy(segmentIndex = k) }
            when (seg) {
                is Segment.Drive -> guide(seg.route, TravelMode.DRIVE, index)
                is Segment.Cycle -> guide(seg.route, TravelMode.CYCLE, index, leg.segments.getOrNull(k + 1) as? Segment.BikeDropoff)
                is Segment.Walk -> guide(seg.route ?: walkRoute(seg.to), TravelMode.WALK, index)
                is Segment.Park -> Unit
                is Segment.TransitRide -> if (!ride(seg, index)) return // missed: the leg restarts after re-planning
                is Segment.BikePickup -> bikePickup(seg.dock)
                is Segment.BikeDropoff -> bikeDropoff(seg.dock)
            }
            k++
        }
        core.stopNavigation()
        handle(rt.onArrived(now()))
    }

    private suspend fun walkRoute(to: LatLng): NavRoute {
        _state.update { it.copy(segment = SegmentUi.Preparing("Finding the walking route…")) }
        val from = _state.value.userLocation ?: location.lastLocation()?.toLatLng()
            ?: withTimeoutOrNull(30_000) { _state.first { it.userLocation != null }.userLocation }
            ?: throw IllegalStateException("No GPS fix yet. Step outside or check location is on.")
        val options = graph.settings.current.plannerSettings().routeOptions
        return graph.routes().route(RouteRequest(TravelMode.WALK, listOf(from, to), options)).legs.single()
    }

    /** Turn-by-turn with Ferrostar, using exactly the route that was previewed (§2.2). */
    private suspend fun guide(route: NavRoute, mode: TravelMode, legIndex: Int, dropoff: Segment.BikeDropoff? = null) {
        val destination = runtime?.plan?.trip?.stops?.getOrNull(legIndex)?.place?.label ?: ""
        recalcAdapter = RouteAdapter.fromWellKnownRouteProvider(
            WellKnownRouteProvider.Valhalla(graph.routeEndpoint.url, route.costing, route.optionsJson),
        )
        val ferrostarRoute = toFerrostar(route)
        if (location.simulate) location.simulated.setRoute(ferrostarRoute)
        navigationViewModel.setDestination(destination)
        _state.update { it.copy(segment = SegmentUi.Guiding(mode, destination)) }
        core.startNavigation(ferrostarRoute)

        val end = route.geometry.last()
        var dockChecked = false
        core.state.first { s ->
            val ts = s.tripState
            if (ts is TripState.Navigating) {
                onProgress(legIndex, ts)
                if (dropoff != null && !dockChecked && ts.progress.durationRemaining < 120) {
                    dockChecked = true
                    scope.launch { recheckDropoff(legIndex, dropoff) }
                }
            }
            ts is TripState.Complete ||
                (ts is TripState.Navigating && ts.remainingSteps.size <= 2 &&
                    Geo.distanceMeters(ts.userLocation.coordinates.let { LatLng(it.lat, it.lng) }, end) < ARRIVAL_RADIUS_M)
        }
    }

    private fun onProgress(legIndex: Int, ts: TripState.Navigating) {
        val now = now()
        if (now - lastProgressAt < 15.seconds) return
        lastProgressAt = now
        val rt = runtime ?: return
        val leg = rt.plan.legs[legIndex]
        val after = leg.segments.drop(_state.value.segmentIndex + 1).fold(Duration.ZERO) { acc, s -> if (s is Segment.Park) acc else acc + s.duration }
        handle(rt.onProgress(now, ts.progress.durationRemaining.seconds + after))
    }

    /**
     * Transit companion: walk to the stop is a separate segment; this waits for the vehicle
     * (TfL live arrivals in London), then alerts "get off at the next stop". Returns false on a
     * missed connection (the leg is re-planned).
     */
    private suspend fun ride(seg: Segment.TransitRide, legIndex: Int): Boolean {
        core.stopNavigation()
        drainActions()
        val tfl = if (seg.board.id != null && London.inGreaterLondon(seg.board.latLng)) graph.tfl() else null
        var boarded = false
        var alerted = false
        var lastArrivals = 0L
        _state.update { it.copy(segment = SegmentUi.TransitWait(seg, emptyList())) }
        while (true) {
            val here = _state.value.userLocation
            val now = now()
            val action = actions.tryReceive().getOrNull()
            if (!boarded) {
                if (tfl != null && System.currentTimeMillis() - lastArrivals > 30_000) {
                    lastArrivals = System.currentTimeMillis()
                    val arrivals = runCatching { tfl.arrivals(seg.board.id!!, seg.line).take(3) }.getOrDefault(emptyList())
                    _state.update { s -> if (s.segment is SegmentUi.TransitWait) s.copy(segment = SegmentUi.TransitWait(seg, arrivals)) else s }
                }
                val movedAway = here != null && Geo.distanceMeters(here, seg.board.latLng) > 250 && now > seg.departAt - 2.minutes
                if (action == UserAction.ON_BOARD || movedAway) {
                    boarded = true
                    _state.update { it.copy(segment = SegmentUi.TransitRide(seg, getOffNext = false)) }
                } else if (here != null && !location.simulate && now > seg.departAt + 4.minutes &&
                    Geo.distanceMeters(here, seg.board.latLng) < 150
                ) {
                    show("Missed the ${seg.line}. Finding the next connection…")
                    retime(legIndex, now)
                    beginLeg(legIndex)
                    return false
                }
            } else {
                val getOffNext = getOffNext(seg, here)
                if (getOffNext && !alerted) {
                    alerted = true
                    val text = "Get off at the next stop, ${seg.alight.name}."
                    speak(text)
                    notifyAlert(text)
                    _state.update { it.copy(segment = SegmentUi.TransitRide(seg, getOffNext = true)) }
                }
                val atAlight = here != null && Geo.distanceMeters(here, seg.alight.latLng) < 120
                if (action == UserAction.OFF_VEHICLE || atAlight) return true
            }
            delay(2_000)
        }
    }

    private fun getOffNext(seg: Segment.TransitRide, here: LatLng?): Boolean {
        here ?: return false
        if (seg.intermediateStops.isNotEmpty()) {
            val stops = seg.intermediateStops + seg.alight
            val nearest = stops.indices.minBy { Geo.distanceMeters(here, stops[it].latLng) }
            return nearest >= stops.lastIndex - 1 && Geo.distanceMeters(here, stops[stops.lastIndex - 1].latLng) < 300 ||
                nearest == stops.lastIndex
        }
        val radius = when (seg.vehicle) {
            TransitVehicle.BUS, TransitVehicle.TRAM -> 500.0
            TransitVehicle.SUBWAY -> 1_200.0
            TransitVehicle.TRAIN -> 2_500.0
        }
        return Geo.distanceMeters(here, seg.alight.latLng) < radius
    }

    /** "Unlock a bike" card (§4.4); unlocking stays in the operator's app. */
    private suspend fun bikePickup(dock: Dock) {
        core.stopNavigation()
        drainActions()
        val live = runCatching { graph.bikes()?.docksNear(dock.latLng, 30.0)?.firstOrNull { it.id == dock.id } }.getOrNull()
        _state.update { it.copy(segment = SegmentUi.BikePickup(live ?: dock)) }
        speak("Unlock a bike at ${dock.name}.")
        while (actions.receive() != UserAction.GOT_BIKE) Unit
    }

    private suspend fun bikeDropoff(dock: Dock) {
        core.stopNavigation()
        drainActions()
        val current = currentDropoff(dock)
        _state.update { it.copy(segment = SegmentUi.BikeDropoff(current)) }
        speak("Dock the bike at ${current.name}.")
        while (actions.receive() != UserAction.DOCKED) Unit
    }

    /** ~2 minutes before the drop-off dock: still has spaces? If not, ride to another one (§4.4). */
    private var dropoffOverride: Dock? = null

    private fun currentDropoff(planned: Dock) = dropoffOverride?.takeIf { it.system == planned.system } ?: planned

    private suspend fun recheckDropoff(legIndex: Int, dropoff: Segment.BikeDropoff) {
        val bikes = graph.bikes() ?: return
        val stop = runtime?.plan?.trip?.stops?.getOrNull(legIndex) ?: return
        val live = runCatching { bikes.docksNear(dropoff.dock.latLng, 30.0) }.getOrNull() ?: return
        if ((live.firstOrNull { it.id == dropoff.dock.id }?.spacesAvailable ?: 1) > 0) return
        val alternative = runCatching { bikes.docksNear(stop.place.latLng, 800.0) }.getOrNull()
            ?.filter { it.spacesAvailable > 0 && it.id != dropoff.dock.id }
            ?.minByOrNull { Geo.distanceMeters(it.latLng, stop.place.latLng) } ?: return show("${dropoff.dock.name} is full and no other dock nearby has space")
        val here = _state.value.userLocation ?: return
        val options = graph.settings.current.plannerSettings().routeOptions
        val ride = runCatching { graph.routes().route(RouteRequest(TravelMode.CYCLE, listOf(here, alternative.latLng), options)).legs.single() }
            .getOrNull() ?: return
        dropoffOverride = alternative
        core.replaceRoute(toFerrostar(ride))
        if (location.simulate) location.simulated.setRoute(toFerrostar(ride))
        show("${dropoff.dock.name} is full. Riding to ${alternative.name} instead.")
        speak("${dropoff.dock.name} is full. Rerouting to ${alternative.name}.")
    }

    // ---- Helpers --------------------------------------------------------------------------------

    private fun toFerrostar(route: NavRoute): Route =
        Route.fromOsrm(route.osrmRouteJson.encodeToByteArray(), route.osrmWaypointsJson.encodeToByteArray(), 6u)

    /** Off-route: Ferrostar asks the same Valhalla, with the same options, for the remaining stop. */
    private suspend fun recalculate(location: UserLocation, waypoints: List<Waypoint>): List<Route> {
        val adapter = recalcAdapter ?: return emptyList()
        val response = ferrostarHttp.call(adapter.generateRequest(location, waypoints))
        val body = response.bodyBytes()
        if (!response.isSuccessful || body == null) return emptyList()
        return adapter.parseResponse(body)
    }

    private fun drainActions() {
        while (actions.tryReceive().isSuccess) Unit
    }

    private fun show(message: String) = _state.update { it.copy(message = message) }

    fun speak(text: String) {
        if (!graph.settings.current.voiceGuidance) return
        tts.onSpokenInstructionTrigger(SpokenInstruction(text, null, 0.0, UUID.randomUUID()))
    }

    private fun notifyAlert(text: String) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(app, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        val nm = app.getSystemService(NotificationManager::class.java)
        val n = NotificationCompat.Builder(app, WaypointApp.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_waypoint)
            .setContentTitle(text)
            .setContentIntent(TripSessionService.openAppIntent(app))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .build()
        runCatching { nm.notify(ALERT_ID, n) }
    }

    private fun now(): Instant = Clock.System.now()

    /** Ferrostar's NavigationViewModel for the phone and car navigation views. */
    private inner class SessionNavigationViewModel : NavigationViewModel {
        private val destination = MutableStateFlow<String?>(null)
        override val navigationUiState: StateFlow<NavigationUiState> =
            combine(core.state, tts.muteState, destination) { s, muted, dest ->
                NavigationUiState.fromFerrostar(s, muted).copy(destination = dest)
            }.stateIn(scope, SharingStarted.Eagerly, NavigationUiState.fromFerrostar(core.state.value, tts.isMuted))

        override fun setDestination(destination: String?) {
            this.destination.value = destination
        }

        override fun toggleMute() = tts.setMuted(!tts.isMuted)

        override fun stopNavigation() = endTrip()
    }

    companion object {
        const val ARRIVAL_RADIUS_M = 25.0
        const val ALERT_ID = 2

        fun modeVerb(mode: TravelMode) = when (mode) {
            TravelMode.DRIVE -> "drive"
            TravelMode.WALK -> "walk"
            TravelMode.CYCLE -> "cycle"
            TravelMode.BIKE_SHARE -> "bike share"
            TravelMode.TRANSIT -> "transit"
        }

        /** Ferrostar's demo-app settings: advance a stop within 100 m, steps by entry and exit. */
        fun navigationConfig() = NavigationControllerConfig(
            waypointAdvance = WaypointAdvanceMode.WaypointWithinRange(100.0),
            stepAdvanceCondition = stepAdvanceDistanceEntryAndExit(30u, 5u, 32u),
            arrivalStepAdvanceCondition = stepAdvanceDistanceToEndOfStep(10u, 32u),
            routeDeviationTracking = RouteDeviationTracking.StaticThreshold(15u, 50.0),
            snappedLocationCourseFiltering = CourseFiltering.SNAP_TO_ROUTE,
        )
    }
}
