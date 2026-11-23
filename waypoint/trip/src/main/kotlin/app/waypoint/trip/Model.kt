package app.waypoint.trip

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Data model from the spec (§3). The user's input (Trip, Stop, ...) is serializable so trips can be
// saved; the planner's output (Leg, Segment, ...) is derived and never edited directly.

@Serializable
data class LatLng(val lat: Double, val lng: Double)

@Serializable
data class Trip(
    val id: String,
    val name: String? = null,
    /** Current location or a fixed place. */
    val origin: Origin = Origin.CurrentLocation,
    /** Leave now / depart at / arrive by. */
    val time: TimeConstraint = TimeConstraint.LeaveNow,
    /** Your order, max [MAX_STOPS], never reordered by the app; last = destination. */
    val stops: List<Stop> = emptyList(),
    /** How to travel when a stop doesn't say. */
    val defaultMode: ModeChoice = ModeChoice.Fixed(TravelMode.DRIVE),
    val defaultContinuation: Continuation = Continuation.Auto,
    /** Expected time at each stop. */
    val defaultStay: Duration = Duration.ZERO,
) {
    init {
        require(stops.size <= MAX_STOPS) { "A trip has at most $MAX_STOPS stops" }
    }

    fun modeTo(stop: Stop): ModeChoice = stop.modeToHere ?: defaultMode
    fun continuationAt(stop: Stop): Continuation = stop.continuation ?: defaultContinuation
    fun stayAt(stop: Stop): Duration = stop.stay ?: defaultStay

    companion object {
        /** Plus the start. Matches the Google Maps app fallback (9 waypoints + destination). */
        const val MAX_STOPS = 10
    }
}

@Serializable
sealed interface Origin {
    @Serializable @SerialName("current")
    data object CurrentLocation : Origin

    @Serializable @SerialName("fixed")
    data class Fixed(val place: Place) : Origin
}

@Serializable
sealed interface TimeConstraint {
    @Serializable @SerialName("now")
    data object LeaveNow : TimeConstraint

    @Serializable @SerialName("depart")
    data class DepartAt(val at: Instant) : TimeConstraint

    @Serializable @SerialName("arrive")
    data class ArriveBy(val at: Instant) : TimeConstraint
}

@Serializable
data class Place(
    val osmId: String? = null,
    val latLng: LatLng,
    val label: String,
    /** Second line shown under the label (street, area). Not used for routing. */
    val address: String? = null,
)

@Serializable
data class Stop(
    val id: String,
    val place: Place,
    /** Car park / entrance to navigate to when driving here; null = the place. */
    val arrivalPoint: LatLng? = null,
    /** How you get TO this stop; null = trip default. */
    val modeToHere: ModeChoice? = null,
    /** null = trip default. */
    val continuation: Continuation? = null,
    /** Expected time here; null = trip default. */
    val stay: Duration? = null,
    /** Driving only; null = settings default. */
    val parkingTime: Duration? = null,
    /** "Park here" / "Lock bike here". */
    val leaveVehicle: Boolean = false,
)

@Serializable
sealed interface Continuation {
    /** Next leg starts by itself after `stay`. */
    @Serializable @SerialName("auto")
    data object Auto : Continuation

    /** Waits for "Start next leg"; `stay` is only an estimate for ETAs. */
    @Serializable @SerialName("manual")
    data object Manual : Continuation

    companion object {
        /** Offered when a stop is switched to Manual (5 / 15 / 30 / 60 min). */
        val MANUAL_STAY_CHOICES: List<Duration> = listOf(5.minutes, 15.minutes, 30.minutes, 60.minutes)
        val DEFAULT_MANUAL_STAY: Duration = 15.minutes
    }
}

@Serializable
enum class TravelMode { DRIVE, WALK, CYCLE, BIKE_SHARE, TRANSIT }

@Serializable
enum class TransitVehicle { BUS, TRAIN, SUBWAY, TRAM }

@Serializable
sealed interface ModeChoice {
    @Serializable @SerialName("fixed")
    data class Fixed(
        val mode: TravelMode,
        val transit: Set<TransitVehicle> = TransitVehicle.entries.toSet(),
    ) : ModeChoice

    @Serializable @SerialName("fastest")
    data class Fastest(val options: FastestOptions = FastestOptions()) : ModeChoice
}

@Serializable
data class FastestOptions(
    val transit: Set<TransitVehicle> = TransitVehicle.entries.toSet(),
    val walk: Boolean = true,
    /** Own bike. */
    val cycle: Boolean = false,
    val bikeShare: Boolean = false,
    val drive: Boolean = false,
    /** Route drives around the Congestion Charge zone. */
    val avoidChargeZone: Boolean = false,
)

// ---- Planner output: derived, never edited directly ----

@Serializable
data class Leg(
    /** null = origin. */
    val fromStopId: String?,
    val toStopId: String,
    val mode: TravelMode,
    val departAt: Instant,
    val arriveAt: Instant,
    val segments: List<Segment>,
    val warnings: List<Warning>,
    /** Fastest only: one-tap override. */
    val runnerUp: LegOption? = null,
    /** True when the mode came from Fastest (shown as "Fastest: Bus"). */
    val chosenByFastest: Boolean = false,
) {
    val duration: Duration get() = arriveAt - departAt
}

/** A computed alternative for a leg (Fastest candidates). */
@Serializable
data class LegOption(
    val mode: TravelMode,
    val departAt: Instant,
    val arriveAt: Instant,
    val segments: List<Segment>,
    val warnings: List<Warning> = emptyList(),
) {
    val duration: Duration get() = arriveAt - departAt
}

@Serializable
sealed interface Segment {
    val duration: Duration

    @Serializable @SerialName("walk")
    data class Walk(
        /** Turn-by-turn route; null when the transit planner only gave a line (fetched when the walk starts). */
        val route: NavRoute?,
        override val duration: Duration,
        val from: LatLng,
        val to: LatLng,
        val geometry: List<LatLng> = route?.geometry ?: listOf(from, to),
        val distanceMeters: Double = route?.distanceMeters ?: 0.0,
    ) : Segment

    @Serializable @SerialName("drive")
    data class Drive(
        val route: NavRoute,
        val rawDuration: Duration,
        val adjustedDuration: Duration,
    ) : Segment {
        override val duration: Duration get() = adjustedDuration
    }

    @Serializable @SerialName("cycle")
    data class Cycle(val route: NavRoute, override val duration: Duration) : Segment

    @Serializable @SerialName("park")
    data class Park(override val duration: Duration) : Segment

    @Serializable @SerialName("ride")
    data class TransitRide(
        val line: String,
        val vehicle: TransitVehicle,
        val headsign: String,
        val board: TransitStop,
        val alight: TransitStop,
        val departAt: Instant,
        val arriveAt: Instant,
        /** Stops between [board] and [alight], for the "get off next stop" alert. */
        val intermediateStops: List<TransitStop> = emptyList(),
        val geometry: List<LatLng> = emptyList(),
        val realTime: Boolean = false,
    ) : Segment {
        override val duration: Duration get() = arriveAt - departAt
    }

    @Serializable @SerialName("pickup")
    data class BikePickup(val dock: Dock, override val duration: Duration) : Segment

    @Serializable @SerialName("dropoff")
    data class BikeDropoff(val dock: Dock, override val duration: Duration) : Segment
}

@Serializable
data class TransitStop(val name: String, val latLng: LatLng, val id: String? = null)

@Serializable
data class Dock(
    val id: String,
    val name: String,
    val latLng: LatLng,
    val bikesAvailable: Int,
    val spacesAvailable: Int,
    val system: String,
)

/**
 * The Valhalla route for one leg, kept exactly as previewed so it can be handed to Ferrostar
 * unchanged at Start (spec §2.2 "Preview = guidance").
 */
@Serializable
data class NavRoute(
    /** Valhalla costing: auto, pedestrian, bicycle. */
    val costing: String,
    /** Valhalla request options (costing options, exclusions, units) for off-route recalculation. */
    val optionsJson: String,
    /** One OSRM-format route object with a single leg (Ferrostar `Route.fromOsrm` input). */
    val osrmRouteJson: String,
    /** The OSRM waypoints for that leg: [from, to]. */
    val osrmWaypointsJson: String,
    val geometry: List<LatLng>,
    val distanceMeters: Double,
    val durationSeconds: Double,
)

@Serializable
enum class Warning {
    /** The drive enters the Congestion Charge zone during charging hours. */
    CHARGE_ZONE,
    /** Your car or bike stays behind at a stop the trip doesn't come back to. */
    VEHICLE_LEFT_BEHIND,
    LATE_FOR_ARRIVE_BY,
    /** No transit journey uses only the allowed vehicles; walking is shown instead. */
    NO_TRANSIT_MATCH,
    NO_BIKE_DOCKS,
    /** Info: drive times use the drive-time factor, not live traffic. */
    NO_LIVE_TRAFFIC,
    /** The routing server can't avoid the charge zone, so the route may enter it. */
    CHARGE_ZONE_NOT_AVOIDED,
    /** Your car doesn't meet the ULEZ standards and the drive enters Greater London. */
    ULEZ,
}

/** The whole planned trip: one timeline, one total ETA. */
@Serializable
data class Plan(
    val trip: Trip,
    val start: LatLng,
    val legs: List<Leg>,
    val planWarnings: List<Warning> = emptyList(),
    /** Arrive by: positive = early, negative = late. */
    val arriveBySlack: Duration? = null,
    /** Arrive by: latest departure that still makes it (estimate). */
    val latestDeparture: Instant? = null,
    val plannedAt: Instant,
) {
    val departAt: Instant get() = legs.firstOrNull()?.departAt ?: plannedAt
    val arriveAt: Instant get() = legs.lastOrNull()?.arriveAt ?: plannedAt
    val allWarnings: Set<Warning> get() = (planWarnings + legs.flatMap { it.warnings }).toSet()
}
