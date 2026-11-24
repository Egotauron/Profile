package app.waypoint.session

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.os.SystemClock
import androidx.car.app.connection.CarConnection
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.waypoint.AppGraph
import app.waypoint.R
import app.waypoint.WaypointApp
import app.waypoint.providers.TflClient
import app.waypoint.trip.Continuation
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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
import uniffi.ferrostar.CourseOverGround
import uniffi.ferrostar.RouteDeviation
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
}

enum class UserAction { ON_BOARD, OFF_VEHICLE }

/** What the trip cards can ask of the running trip (the session in the app, a stub in previews). */
interface TripControls {
    fun startNextLeg()
    fun skipNextStop()
    fun pauseCountdown()
    fun confirm(action: UserAction)
}

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
class TripSession(private val app: Application, private val graph: AppGraph) : TripControls {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /** The trip planned in the builder, offered to Android Auto ("current trip"). */
    val preparedPlan = MutableStateFlow<Plan?>(null)

    val location = SwitchableLocationProvider(app, graph.settings.current.simulateLocation)
    val tts = AndroidTtsObserver(app).also { it.start() }

    /** Turn-by-turn (Ferrostar) behind the spec's NavigationEngine interface. */
    val engine = FerrostarNavigationEngine(graph.http, location, { graph.routeEndpoint.url }, tts)
    val core: FerrostarCore get() = engine.core

    val navigationViewModel: NavigationViewModel = SessionNavigationViewModel()

    /**
     * The same, for the phone and car maps, with the car moved on between GPS fixes
     * ([LocationPredictor]) five times a second. It only ticks while a map is showing it.
     */
    val mapViewModel: NavigationViewModel = MapNavigationViewModel(navigationViewModel)

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
        scope.launch { runCatching { graph.music.onTripStart() } }
    }

    override fun startNextLeg() {
        runtime?.let { handle(it.startNextLeg(now())) }
    }

    override fun skipNextStop() {
        runtime?.let { handle(it.skipNextStop()) }
    }

    override fun pauseCountdown() {
        runtime?.pauseCountdown()
        publish()
    }

    override fun confirm(action: UserAction) {
        actions.trySend(action)
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun endTrip(silently: Boolean = false) {
        legJob?.cancel()
        tickJob?.cancel()
        trackJob?.cancel()
        engine.stop()
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
        engine.stop()
        val last = runtime?.plan?.trip?.stops?.lastOrNull()?.place?.label
        speak("You've arrived${last?.let { " at $it" } ?: ""}. Trip complete.")
        publish()
        trackJob?.cancel()
        app.stopService(Intent(app, TripSessionService::class.java))
    }

    // ---- Arrival and continuation (§4.2) --------------------------------------------------------

    private fun onArrival(stopIndex: Int) {
        val rt = runtime ?: return
        engine.stop()
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
                is Segment.Cycle -> guide(seg.route, TravelMode.CYCLE, index)
                is Segment.Walk -> guide(seg.route ?: walkRoute(seg.to), TravelMode.WALK, index)
                is Segment.Park -> Unit
                is Segment.TransitRide -> if (!ride(seg, index)) return // missed: the leg restarts after re-planning
            }
            k++
        }
        engine.stop()
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

    /** Turn-by-turn along exactly the route that was previewed (§2.2). */
    private suspend fun guide(route: NavRoute, mode: TravelMode, legIndex: Int) {
        val destination = runtime?.plan?.trip?.stops?.getOrNull(legIndex)?.place?.label ?: ""
        navigationViewModel.setDestination(destination)
        _state.update { it.copy(segment = SegmentUi.Guiding(mode, destination)) }
        engine.guide(route, destination) { p -> reportProgress(legIndex, p.durationRemaining) }
    }

    /**
     * Feeds the runtime the time still needed to reach this leg's stop: what's left of the current
     * segment plus the segments after it (not parking, which the plan adds itself). Running more than
     * 5 minutes behind re-plans the legs after this one (§4.1).
     */
    private fun reportProgress(legIndex: Int, segmentRemaining: Duration) {
        val now = now()
        if (now - lastProgressAt < 15.seconds) return
        lastProgressAt = now
        val rt = runtime ?: return
        val leg = rt.plan.legs.getOrNull(legIndex) ?: return
        val after = leg.segments.drop(_state.value.segmentIndex + 1).fold(Duration.ZERO) { acc, s -> if (s is Segment.Park) acc else acc + s.duration }
        handle(rt.onProgress(now, segmentRemaining + after))
    }

    /**
     * Transit companion: walk to the stop is a separate segment; this waits for the vehicle
     * (TfL live arrivals in London), then alerts "get off at the next stop". Returns false on a
     * missed connection (the leg is re-planned).
     */
    private suspend fun ride(seg: Segment.TransitRide, legIndex: Int): Boolean {
        engine.stop()
        drainActions()
        val tfl = if (seg.board.id != null && London.inGreaterLondon(seg.board.latLng)) graph.tfl() else null
        var boarded = false
        var alerted = false
        var lastArrivals = 0L
        // How late the vehicle is: from TfL live arrivals while waiting, from the time you boarded after.
        var delay = Duration.ZERO
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
                    // The first of your line due no earlier than a minute before the timetable.
                    arrivals.firstOrNull { it.expected >= seg.departAt - 1.minutes }?.let { delay = (it.expected - seg.departAt).coerceAtLeast(Duration.ZERO) }
                }
                if (now > seg.departAt + delay) delay = now - seg.departAt
                reportProgress(legIndex, (seg.arriveAt + delay - now).coerceAtLeast(Duration.ZERO))
                val movedAway = here != null && Geo.distanceMeters(here, seg.board.latLng) > 250 && now > seg.departAt - 2.minutes
                if (action == UserAction.ON_BOARD || movedAway) {
                    boarded = true
                    delay = (now - seg.departAt).coerceAtLeast(Duration.ZERO)
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
                if (now > seg.arriveAt + delay) delay = now - seg.arriveAt
                reportProgress(legIndex, (seg.arriveAt + delay - now).coerceAtLeast(Duration.ZERO))
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

    // ---- Helpers --------------------------------------------------------------------------------

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

    /** One line of GPS details for the map (Settings → Show GPS details), else null. */
    val gpsDetails = MutableStateFlow<String?>(null)

    /**
     * What the maps show: every fix, where the GPS puts it, and between fixes the car moves on
     * along its course at the speed the GPS reports ([LocationPredictor]; nothing is predicted when
     * stopped). The map's rotation keeps the direction from the last time you were clearly moving:
     * below that the GPS direction of travel is noise, not a measurement.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private inner class MapNavigationViewModel(private val base: NavigationViewModel) : NavigationViewModel by base {
        private val predictor = LocationPredictor()
        private var steadyCourse: CourseOverGround? = null
        private var lastFix: UserLocation? = null
        private var lastFixAt = 0L

        override val navigationUiState: StateFlow<NavigationUiState> =
            base.navigationUiState.flatMapLatest { ui ->
                val raw = ui.location
                if (raw == null || !ui.isNavigating()) return@flatMapLatest flowOf(ui)
                val speed = location.recentSpeed()
                val moving = (speed ?: 0.0) >= STEADY_COURSE_MPS
                if (moving && raw.courseOverGround != null) steadyCourse = raw.courseOverGround
                val fix = if (moving) raw else raw.copy(courseOverGround = steadyCourse ?: raw.courseOverGround)
                describe(raw, speed, ui)
                flow {
                    predictor.update(fix, SystemClock.elapsedRealtime())
                    while (true) {
                        emit(ui.copy(location = predictor.predict(SystemClock.elapsedRealtime())))
                        delay(MAP_FRAME_MS)
                    }
                }
            }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), base.navigationUiState.value)

        /** The GPS details line: accuracy, speed, fix rate, how far this fix moved, on/off route. */
        private fun describe(raw: UserLocation, speed: Double?, ui: NavigationUiState) {
            if (!graph.settings.current.showGpsDetails) {
                gpsDetails.value = null
                return
            }
            val now = SystemClock.elapsedRealtime()
            val every = if (lastFixAt == 0L) null else (now - lastFixAt) / 1000.0
            val moved = lastFix?.let { metresBetween(it, raw) }
            lastFixAt = now
            lastFix = raw
            gpsDetails.value = listOfNotNull(
                "±%.0f m".format(raw.horizontalAccuracy),
                speed?.let { "%.1f m/s".format(it) } ?: "no speed",
                every?.let { "fix %.1fs".format(it) },
                moved?.let { "moved %.1f m".format(it) },
                if (location.isStill()) "still" else "moving",
                if (ui.routeDeviation is RouteDeviation.NoDeviation) "on route" else "off route",
            ).joinToString(" · ")
        }
    }

    companion object {
        const val ALERT_ID = 2
        private const val MAP_FRAME_MS = 200L
        /** Above this (m/s, a brisk walk) the GPS direction of travel is a real measurement. */
        private const val STEADY_COURSE_MPS = 2.5


        fun modeVerb(mode: TravelMode) = when (mode) {
            TravelMode.DRIVE -> "drive"
            TravelMode.WALK -> "walk"
            TravelMode.CYCLE -> "cycle"
            TravelMode.TRANSIT -> "transit"
        }
    }
}
