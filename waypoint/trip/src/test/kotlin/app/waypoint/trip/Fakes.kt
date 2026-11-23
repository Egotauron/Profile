package app.waypoint.trip

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Straight-line router: constant speed per mode, records every request. */
class FakeRoutes(
    private val speeds: Map<TravelMode, Double> = mapOf(
        TravelMode.DRIVE to 10.0,
        TravelMode.WALK to 1.4,
        TravelMode.CYCLE to 4.5,
    ),
    private val ignoreExclusions: Boolean = false,
) : RouteProvider {
    val requests = mutableListOf<RouteRequest>()

    override suspend fun route(request: RouteRequest): RouteResult {
        synchronized(requests) { requests += request }
        val legs = request.points.zipWithNext().map { (a, b) ->
            val d = Geo.distanceMeters(a, b)
            NavRoute(
                costing = request.mode.name.lowercase(),
                optionsJson = "{}",
                osrmRouteJson = "{}",
                osrmWaypointsJson = "[]",
                geometry = listOf(a, b),
                distanceMeters = d,
                durationSeconds = d / speeds.getValue(request.mode),
            )
        }
        return RouteResult(legs, exclusionsIgnored = ignoreExclusions && request.options.excludePolygons.isNotEmpty())
    }
}

/** Returns one itinerary per vehicle: walk 3 min, ride [rideTime], walk 3 min. */
class FakeTransit(
    private val vehicles: List<TransitVehicle> = listOf(TransitVehicle.SUBWAY),
    private val rideTime: Duration = 600.seconds,
    private val wait: Duration = 120.seconds,
) : TransitProvider {
    val requests = mutableListOf<Instant>()

    override suspend fun plan(from: LatLng, to: LatLng, departAt: Instant): List<TransitItinerary> {
        synchronized(requests) { requests += departAt }
        return vehicles.map { v ->
            val board = departAt + 180.seconds + wait
            val alight = board + rideTime
            TransitItinerary(
                departAt = departAt,
                arriveAt = alight + 180.seconds,
                segments = listOf(
                    Segment.Walk(null, 180.seconds, from, from),
                    Segment.TransitRide(
                        line = if (v == TransitVehicle.BUS) "14" else "Piccadilly",
                        vehicle = v,
                        headsign = "Cockfosters",
                        board = TransitStop("Board", from),
                        alight = TransitStop("Alight", to),
                        departAt = board,
                        arriveAt = alight,
                    ),
                    Segment.Walk(null, 180.seconds, to, to),
                ),
            )
        }
    }
}


fun place(label: String, lat: Double, lng: Double) = Place(null, LatLng(lat, lng), label)

fun stop(id: String, p: Place, block: Stop.() -> Stop = { this }) = Stop(id, p).block()
