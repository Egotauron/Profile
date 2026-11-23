package app.waypoint.trip

import kotlin.time.Duration
import kotlin.time.Instant

// Providers sit behind interfaces (spec §2.2) so Stadia → on-device Valhalla, Photon public →
// self-hosted, etc. are configuration changes.

class ProviderException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Options shared by every Valhalla request in a trip. */
data class RouteOptions(
    val avoidTolls: Boolean = false,
    val avoidMotorways: Boolean = false,
    val avoidFerries: Boolean = false,
    /** Areas the route must not enter (Valhalla `exclude_polygons`). */
    val excludePolygons: List<List<LatLng>> = emptyList(),
    /** Voice and banner language. */
    val language: String = "en-GB",
    val distanceUnits: DistanceUnits = DistanceUnits.MILES,
)

enum class DistanceUnits { MILES, KILOMETERS }

data class RouteRequest(
    /** DRIVE, WALK or CYCLE. */
    val mode: TravelMode,
    /** Start followed by the ordered stops; every stop is a `break` location. */
    val points: List<LatLng>,
    val options: RouteOptions = RouteOptions(),
) {
    init {
        require(mode == TravelMode.DRIVE || mode == TravelMode.WALK || mode == TravelMode.CYCLE)
        require(points.size >= 2)
    }
}

data class RouteResult(
    /** One route per leg, in order (points.size - 1 entries). */
    val legs: List<NavRoute>,
    /** The server refused `exclude_polygons` (e.g. perimeter limit); the route ignores them. */
    val exclusionsIgnored: Boolean = false,
)

interface RouteProvider {
    suspend fun route(request: RouteRequest): RouteResult
}

interface PlaceProvider {
    suspend fun search(query: String, near: LatLng?, limit: Int = 8): List<Place>
    suspend fun reverse(point: LatLng): Place?
}

/** A transit journey from a planner (TfL, Transitous), already mapped to our segments. */
data class TransitItinerary(
    val departAt: Instant,
    val arriveAt: Instant,
    /** Walk and TransitRide segments in order. */
    val segments: List<Segment>,
) {
    val vehicles: Set<TransitVehicle>
        get() = segments.filterIsInstance<Segment.TransitRide>().map { it.vehicle }.toSet()
    val duration: Duration get() = arriveAt - departAt
}

interface TransitProvider {
    /** One request with alternatives (spec §4.3); filtering by vehicle is done by the planner. */
    suspend fun plan(from: LatLng, to: LatLng, departAt: Instant): List<TransitItinerary>

    /** Whether this provider covers the journey (TfL: both ends in Greater London). */
    fun covers(from: LatLng, to: LatLng): Boolean = true
}

interface BikeShareProvider {
    /** Docks within [radiusMeters] of [point], with live bike and space counts. */
    suspend fun docksNear(point: LatLng, radiusMeters: Double): List<Dock>
}
