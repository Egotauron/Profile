package app.waypoint.session

import app.waypoint.trip.Geo
import app.waypoint.trip.GuidanceProgress
import app.waypoint.trip.LatLng
import app.waypoint.trip.NavRoute
import app.waypoint.trip.NavigationEngine
import android.os.SystemClock
import com.stadiamaps.ferrostar.core.CorrectiveAction
import com.stadiamaps.ferrostar.core.CustomRouteProvider
import com.stadiamaps.ferrostar.core.FerrostarCore
import com.stadiamaps.ferrostar.core.RouteDeviationHandler
import com.stadiamaps.ferrostar.core.SpokenInstructionObserver
import com.stadiamaps.ferrostar.core.extensions.fromOsrm
import com.stadiamaps.ferrostar.core.http.OkHttpClientProvider
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import uniffi.ferrostar.CourseFiltering
import uniffi.ferrostar.CourseOverGround
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
    // A new route is only useful if it comes quickly: give up on a slow server after 8 s, so the
    // next attempt isn't blocked behind it.
    private val rerouteGate = RerouteGate()

    private val ferrostarHttp = OkHttpClientProvider(http.newBuilder().callTimeout(8, TimeUnit.SECONDS).build())
    private var recalcAdapter: RouteAdapter? = null

    /** Ferrostar's core, also read by the phone and car navigation views. */
    val core: FerrostarCore = FerrostarCore(
        customRouteProvider = CustomRouteProvider { loc, wps -> recalculate(loc, wps) },
        httpClient = ferrostarHttp,
        locationProvider = location,
        navigationControllerConfig = navigationConfig(),
    ).apply {
        spokenInstructionObserver = voice
        // Ferrostar waits 5 s and 50 m between attempts; off route in town that's a block too far.
        minimumTimeBeforeRecalculation = 3.seconds
        minimumMovementBeforeRecalculation = 25.0
        deviationHandler = RouteDeviationHandler { _, _, waypoints ->
            if (rerouteGate.shouldReroute(SystemClock.elapsedRealtime(), this@FerrostarNavigationEngine.location.recentSpeed())) CorrectiveAction.GetNewRoutes(waypoints)
            else CorrectiveAction.DoNothing
        }
    }

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

    /**
     * The new route starts from where you are, facing the way you're going: Valhalla is given your
     * heading, so it continues ahead and turns around properly instead of asking for a U-turn. A fix
     * without a direction gets the one from your last few fixes.
     */
    private suspend fun recalculate(location: UserLocation, waypoints: List<Waypoint>): List<Route> {
        val adapter = recalcAdapter ?: return emptyList()
        val facing = if (location.courseOverGround != null) location
        else this.location.recentCourse()?.let { location.copy(courseOverGround = CourseOverGround(it.roundToInt().mod(360).toUShort(), null)) } ?: location
        val response = ferrostarHttp.call(adapter.generateRequest(facing, waypoints))
        val body = response.bodyBytes()
        if (!response.isSuccessful || body == null) return emptyList()
        return adapter.parseResponse(body)
    }

    companion object {
        const val ARRIVAL_RADIUS_M = 25.0

        /** Advance a stop within 100 m, steps by entry and exit (Ferrostar's demo values). */
        fun navigationConfig() = NavigationControllerConfig(
            waypointAdvance = WaypointAdvanceMode.WaypointWithinRange(100.0),
            stepAdvanceCondition = stepAdvanceDistanceEntryAndExit(30u, 5u, 32u),
            arrivalStepAdvanceCondition = stepAdvanceDistanceToEndOfStep(10u, 32u),
            // Off route once more than 35 m from it, on any fix within 25 m accuracy. Ferrostar's
            // demo values (15 m, 50 m) ignore most fixes in town and react a block late.
            routeDeviationTracking = RouteDeviationTracking.StaticThreshold(25u, 35.0),
            snappedLocationCourseFiltering = CourseFiltering.SNAP_TO_ROUTE,
        )
    }
}
