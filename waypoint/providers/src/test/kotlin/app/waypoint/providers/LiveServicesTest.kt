package app.waypoint.providers

import app.waypoint.trip.DriveTimeCalibration
import app.waypoint.trip.LatLng
import app.waypoint.trip.ModeChoice
import app.waypoint.trip.FastestOptions
import app.waypoint.trip.Place
import app.waypoint.trip.PlannerSettings
import app.waypoint.trip.RouteOptions
import app.waypoint.trip.RouteRequest
import app.waypoint.trip.Stop
import app.waypoint.trip.TravelMode
import app.waypoint.trip.Trip
import app.waypoint.trip.TripPlanner
import app.waypoint.trip.Warning
import app.waypoint.trip.London
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.runBlocking
import app.waypoint.trip.Geo
import org.junit.Assume.assumeTrue

/**
 * Calls the real public services. Run with: ./gradlew :providers:test -Pwaypoint.live=true
 */
class LiveServicesTest {
    private fun live() = assumeTrue(System.getProperty("waypoint.live") == "true")

    private val chelsea = LatLng(51.4869, -0.1696)
    private val westminster = LatLng(51.4994, -0.1245)
    private val museum = LatLng(51.5194, -0.1270)

    @Test
    fun valhallaMultiStopOnThePublicServer() = runBlocking {
        live()
        val result = ValhallaRouteProvider(ValhallaRouteProvider.Endpoint.FOSSGIS)
            .route(RouteRequest(TravelMode.DRIVE, listOf(chelsea, westminster, museum)))
        assertEquals(2, result.legs.size)
        assertTrue(result.legs.all { it.durationSeconds > 60 })
    }

    @Test
    fun chargeZoneAvoidanceFallsBackOnThePublicServer() = runBlocking {
        live()
        val result = ValhallaRouteProvider(ValhallaRouteProvider.Endpoint.FOSSGIS).route(
            RouteRequest(TravelMode.DRIVE, listOf(chelsea, LatLng(51.5265, -0.0780)), RouteOptions(excludePolygons = listOf(London.congestionChargeZone))),
        )
        assertTrue(result.exclusionsIgnored)
    }

    @Test
    fun photonPutsANumberedAddressAtItsNumber() = runBlocking {
        live()
        val top = PhotonPlaceProvider().search("406A Kings Road", chelsea, 5).first()
        println("406A Kings Road -> ${top.label} (${top.address}) at ${top.latLng}")
        assertEquals("406A King's Road", top.label)
        assertTrue(Geo.distanceMeters(top.latLng, LatLng(51.4836, -0.1772)) < 60, "at no. 406 King's Road")
    }

    @Test
    fun photonFindsTheBritishMuseum() = runBlocking {
        live()
        val places = PhotonPlaceProvider().search("british museum", chelsea, 3)
        assertTrue(places.any { it.label == "British Museum" })
    }

    @Test
    fun tflAnswers() = runBlocking {
        live()
        val tfl = TflClient()
        val its = tfl.journey(chelsea, museum, Clock.System.now() + 1.hours)
        assertTrue(its.isNotEmpty())
    }

    @Test
    fun transitousAnswersOutsideLondon() = runBlocking {
        live()
        // Oxford station to Headington.
        val its = TransitousTransitProvider().plan(LatLng(51.7535, -1.2700), LatLng(51.7600, -1.2100), Clock.System.now() + 1.hours)
        assertTrue(its.isNotEmpty())
    }

    @Test
    fun fullFastestPlanAcrossLondon() = runBlocking {
        live()
        val tfl = TflClient()
        val planner = TripPlanner(
            routes = ValhallaRouteProvider(ValhallaRouteProvider.Endpoint.FOSSGIS),
            transit = CompositeTransitProvider(TflTransitProvider(tfl), TransitousTransitProvider()),
            calibration = { DriveTimeCalibration() },
            settings = { PlannerSettings() },
        )
        val trip = Trip(
            id = "live",
            stops = listOf(
                Stop("1", Place(null, westminster, "Westminster")),
                Stop("2", Place(null, museum, "British Museum")),
            ),
            defaultMode = ModeChoice.Fastest(FastestOptions(cycle = true)),
        )
        val plan = planner.plan(trip, chelsea, Clock.System.now() + 1.hours)
        println(plan.legs.joinToString("\n") { "${it.mode} ${it.duration} runner-up=${it.runnerUp?.mode} ${it.warnings}" })
        assertEquals(2, plan.legs.size)
        assertTrue(plan.legs.none { Warning.NO_TRANSIT_MATCH in it.warnings })
    }
}
