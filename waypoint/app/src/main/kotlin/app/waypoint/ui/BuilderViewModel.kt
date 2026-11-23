package app.waypoint.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.waypoint.data.Favourite
import app.waypoint.data.Preset
import app.waypoint.graph
import app.waypoint.session.currentLocation
import app.waypoint.trip.Continuation
import app.waypoint.trip.LatLng
import app.waypoint.trip.ModeChoice
import app.waypoint.trip.Origin
import app.waypoint.trip.Place
import app.waypoint.trip.Plan
import app.waypoint.trip.Stop
import app.waypoint.trip.TimeConstraint
import app.waypoint.trip.TravelMode
import app.waypoint.trip.Trip
import java.util.UUID
import kotlin.time.Clock
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface PlanState {
    data object Empty : PlanState
    data object Planning : PlanState
    data class Ready(val plan: Plan) : PlanState
    data class Failed(val message: String) : PlanState
}

/** Where a picked place goes. */
sealed interface PickTarget {
    data object AddStop : PickTarget
    data object Origin : PickTarget
    data class ReplaceStop(val stopId: String) : PickTarget
    data class CarPark(val stopId: String) : PickTarget
}

/** The trip builder (spec §6.1). Re-plans about a second after you stop editing. */
@OptIn(FlowPreview::class)
class BuilderViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph

    private val _trip = MutableStateFlow(blankTrip())
    val trip: StateFlow<Trip> = _trip.asStateFlow()

    private val _plan = MutableStateFlow<PlanState>(PlanState.Empty)
    val plan: StateFlow<PlanState> = _plan.asStateFlow()

    private val _here = MutableStateFlow<LatLng?>(null)
    val here: StateFlow<LatLng?> = _here.asStateFlow()

    var pickTarget: PickTarget = PickTarget.AddStop

    val favourites: StateFlow<List<Favourite>> = graph.repo.favourites.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val recents: StateFlow<List<Place>> = graph.repo.recents.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val savedTrips: StateFlow<List<Trip>> = graph.repo.savedTrips.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val presets: StateFlow<List<Preset>> = graph.repo.presets.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var planJob: Job? = null

    /** Screenshot tests: show this trip and plan without asking any service. */
    private var previewOnly = false

    @androidx.annotation.VisibleForTesting
    internal fun showForPreview(trip: Trip, plan: Plan, here: LatLng?) {
        previewOnly = true
        planJob?.cancel()
        _here.value = here
        _trip.value = trip
        _plan.value = PlanState.Ready(plan)
    }

    init {
        refreshLocation()
        viewModelScope.launch {
            _trip.drop(1).distinctUntilChanged().debounce(900).collect { replan() }
        }
    }

    private fun blankTrip(): Trip {
        val s = graph.settings.current
        return Trip(
            id = UUID.randomUUID().toString(),
            defaultMode = ModeChoice.Fixed(TravelMode.DRIVE),
            defaultContinuation = s.defaultContinuation,
            defaultStay = s.defaultStay,
        )
    }

    fun refreshLocation() {
        if (previewOnly) return
        viewModelScope.launch { getApplication<Application>().currentLocation()?.let { _here.value = it } }
    }

    fun replan() {
        if (previewOnly) return
        planJob?.cancel()
        val trip = _trip.value
        if (trip.stops.isEmpty()) {
            _plan.value = PlanState.Empty
            graph.session.preparedPlan.value = null
            return
        }
        planJob = viewModelScope.launch {
            _plan.value = PlanState.Planning
            val start = when (val o = trip.origin) {
                is Origin.Fixed -> o.place.latLng
                Origin.CurrentLocation -> getApplication<Application>().currentLocation()?.also { _here.value = it } ?: _here.value
            }
            if (start == null) {
                _plan.value = PlanState.Failed("Waiting for your location. Allow location access or choose a start.")
                return@launch
            }
            _plan.value = runCatching { graph.planner().plan(trip, start, Clock.System.now()) }
                .fold(
                    onSuccess = { plan ->
                        graph.session.preparedPlan.value = plan
                        PlanState.Ready(plan)
                    },
                    onFailure = { PlanState.Failed(it.message ?: "Couldn't plan this trip") },
                )
        }
    }

    // ---- Editing ------------------------------------------------------------------------------

    fun edit(change: (Trip) -> Trip) = _trip.update(change)

    fun pick(place: Place, arrivalPoint: LatLng? = null) {
        viewModelScope.launch { graph.repo.touchRecent(place) }
        when (val target = pickTarget) {
            PickTarget.AddStop -> edit { t ->
                if (t.stops.size >= Trip.MAX_STOPS) t
                else t.copy(stops = t.stops + Stop(UUID.randomUUID().toString(), place, arrivalPoint = arrivalPoint))
            }
            PickTarget.Origin -> edit { it.copy(origin = Origin.Fixed(place)) }
            is PickTarget.ReplaceStop -> updateStop(target.stopId) { it.copy(place = place, arrivalPoint = arrivalPoint ?: it.arrivalPoint) }
            is PickTarget.CarPark -> updateStop(target.stopId) { it.copy(arrivalPoint = place.latLng) }
        }
    }

    fun useCurrentLocationAsOrigin() = edit { it.copy(origin = Origin.CurrentLocation) }

    fun updateStop(id: String, change: (Stop) -> Stop) = edit { t ->
        t.copy(stops = t.stops.map { if (it.id == id) change(it) else it })
    }

    fun removeStop(id: String) = edit { t -> t.copy(stops = t.stops.filterNot { it.id == id }) }

    fun moveStop(id: String, delta: Int) = edit { t ->
        val i = t.stops.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in t.stops.indices) t
        else t.copy(stops = t.stops.toMutableList().apply { add(j, removeAt(i)) })
    }

    /** Manual asks roughly how long you'll stay (5 / 15 / 30 / 60 min; default 15). */
    fun setContinuation(id: String, c: Continuation?) = updateStop(id) { s ->
        s.copy(continuation = c, stay = if (c == Continuation.Manual && s.stay == null) Continuation.DEFAULT_MANUAL_STAY else s.stay)
    }

    fun setTime(t: TimeConstraint) = edit { it.copy(time = t) }

    fun setDefaultMode(m: ModeChoice) = edit { it.copy(defaultMode = m) }

    fun fastestDefaults() = graph.settings.current.fastestDefaults

    /** Fastest only: one-tap override with the runner-up. */
    fun useRunnerUp(legIndex: Int) {
        val p = (_plan.value as? PlanState.Ready)?.plan ?: return
        val runnerUp = p.legs.getOrNull(legIndex)?.runnerUp ?: return
        val stopId = p.legs[legIndex].toStopId
        updateStop(stopId) { it.copy(modeToHere = ModeChoice.Fixed(runnerUp.mode)) }
    }

    fun clear() {
        _trip.value = blankTrip()
    }

    fun load(trip: Trip) {
        _trip.value = trip
    }

    fun save(name: String) {
        val t = _trip.value.copy(name = name.ifBlank { "Trip" })
        _trip.value = t
        viewModelScope.launch { graph.repo.saveTrip(t) }
    }

    fun deleteSaved(id: String) = viewModelScope.launch { graph.repo.deleteTrip(id) }

    fun favourite(place: Place, arrivalPoint: LatLng? = null) = viewModelScope.launch { graph.repo.saveFavourite(place, arrivalPoint) }

    fun unfavourite(id: String) = viewModelScope.launch { graph.repo.deleteFavourite(id) }

    fun savePresetFromTrip(name: String) {
        val t = _trip.value
        viewModelScope.launch {
            graph.repo.savePreset(Preset(UUID.randomUUID().toString(), name, t.defaultMode, t.defaultContinuation, t.defaultStay))
        }
    }

    fun applyPreset(p: Preset) = edit { it.copy(defaultMode = p.defaultMode, defaultContinuation = p.defaultContinuation, defaultStay = p.defaultStay) }

    fun deletePreset(id: String) = viewModelScope.launch { graph.repo.deletePreset(id) }
}
