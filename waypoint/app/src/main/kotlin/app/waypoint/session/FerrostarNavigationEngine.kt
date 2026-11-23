package app.waypoint.session

import app.waypoint.trip.Geo
import app.waypoint.trip.GuidanceProgress
import app.waypoint.trip.LatLng
import app.waypoint.trip.NavRoute
import app.waypoint.trip.NavigationEngine
import com.stadiamaps.ferrostar.core.CustomRouteProvider
import com.stadiamaps.ferrostar.core.FerrostarCore
import com.stadiamaps.ferrostar.core.SpokenInstructionObserver
import com.stadiamaps.ferrostar.core.extensions.fromOsrm
import com.stadiamaps.ferrostar.core.http.OkHttpClientProvider
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import uniffi.ferrostar.CourseFiltering
import uniffi.ferrostar.NavigationControllerConfig
import uniffi.ferrostar.Route
import uniffi.ferrostar.RouteAdapter
import uniffi.ferrostar.RouteDeviationTracking
import uniffi.ferrostar.TripState
import uniffi.ferrostar.UserLocation
import uniffi.ferrostar.Waypoint
import uniffi.ferrostar.WaypointAdvanceMode
import uniffi.ferrostar.WellKnownRouteProvider
import uniffi.ferrostar.stepAdvanceDistanceEntryAndExit
import uniffi.ferrostar.stepAdvanceDistanceToEndOfStep

/**
 * [NavigationEngine] backed by Ferrostar (spec §2.1). It's handed the exact route that was
 * previewed; off route, it asks the same Valhalla server, with the same options, for a new one.
 */
class FerrostarNavigationEngine(
    http: OkHttpClient,
    private val location: SwitchableLocationProvider,
    /** The Valhalla endpoint in use (Stadia with your key, or the public server). */
    private val endpoint: () -> String,
    voice: SpokenInstructionObserver,
) : NavigationEngine {
    private val ferrostarHttp = OkHttpClientProvider(http)
    private var recalcAdapter: RouteAdapter? = null

    /** Ferrostar's core, also read by the phone and car navigation views. */
    val core: FerrostarCore = FerrostarCore(
        customRouteProvider = CustomRouteProvider { loc, wps -> recalculate(loc, wps) },
        httpClient = ferrostarHttp,
        locationProvider = location,
        navigationControllerConfig = navigationConfig(),
    ).apply { spokenInstructionObserver = voice }

    override suspend fun guide(route: NavRoute, destination: String, onProgress: (GuidanceProgress) -> Unit) {
        recalcAdapter = RouteAdapter.fromWellKnownRouteProvider(
            WellKnownRouteProvider.Valhalla(endpoint(), route.costing, route.optionsJson),
        )
        val ferrostarRoute = toFerrostar(route)
        if (location.simulate) location.simulated.setRoute(ferrostarRoute)
        core.startNavigation(ferrostarRoute)

        val end = route.geometry.last()
        core.state.first { s ->
            val ts = s.tripState
            if (ts is TripState.Navigating) {
                val here = ts.userLocation.coordinates.let { LatLng(it.lat, it.lng) }
                onProgress(GuidanceProgress(here, ts.progress.durationRemaining.seconds, ts.progress.distanceRemaining))
            }
            // Ferrostar's arrival, or within a few metres of the end on the last steps.
            ts is TripState.Complete ||
                (ts is TripState.Navigating && ts.remainingSteps.size <= 2 &&
                    Geo.distanceMeters(ts.userLocation.coordinates.let { LatLng(it.lat, it.lng) }, end) < ARRIVAL_RADIUS_M)
        }
    }

    override fun replaceRoute(route: NavRoute) {
        val ferrostarRoute = toFerrostar(route)
        recalcAdapter = RouteAdapter.fromWellKnownRouteProvider(
            WellKnownRouteProvider.Valhalla(endpoint(), route.costing, route.optionsJson),
        )
        core.replaceRoute(ferrostarRoute)
        if (location.simulate) location.simulated.setRoute(ferrostarRoute)
    }

    override fun stop() = core.stopNavigation()

    private fun toFerrostar(route: NavRoute): Route =
        Route.fromOsrm(route.osrmRouteJson.encodeToByteArray(), route.osrmWaypointsJson.encodeToByteArray(), 6u)

    private suspend fun recalculate(location: UserLocation, waypoints: List<Waypoint>): List<Route> {
        val adapter = recalcAdapter ?: return emptyList()
        val response = ferrostarHttp.call(adapter.generateRequest(location, waypoints))
        val body = response.bodyBytes()
        if (!response.isSuccessful || body == null) return emptyList()
        return adapter.parseResponse(body)
    }

    companion object {
        const val ARRIVAL_RADIUS_M = 25.0

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
