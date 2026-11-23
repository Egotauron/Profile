package app.waypoint.providers

import app.waypoint.trip.LatLng
import app.waypoint.trip.ProviderException
import app.waypoint.trip.RouteOptions
import app.waypoint.trip.RouteRequest
import app.waypoint.trip.Segment
import app.waypoint.trip.TransitVehicle
import app.waypoint.trip.TravelMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer

class ProvidersTest {
    private fun fixture(name: String) = javaClass.getResource("/fixtures/$name")!!.readText()

    private val chelsea = LatLng(51.4869, -0.1696)
    private val stops = listOf(LatLng(51.4994, -0.1245), LatLng(51.5081, -0.0759), LatLng(51.5194, -0.1270))

    @Test
    fun valhallaRequestMatchesFerrostarAndParsesPerLeg() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(200).body(fixture("valhalla_auto_3stops.json")).build())
            server.start()
            val provider = ValhallaRouteProvider(ValhallaRouteProvider.Endpoint(server.url("/route").toString(), 10, "test"))
            val result = provider.route(
                RouteRequest(TravelMode.DRIVE, listOf(chelsea) + stops, RouteOptions(avoidTolls = true)),
            )
            assertEquals(3, result.legs.size)
            assertEquals(listOf(801, 987, 904), result.legs.map { kotlin.math.round(it.durationSeconds).toInt() })

            val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
            assertEquals("osrm", body["format"]!!.jsonPrimitive.content)
            assertEquals("auto", body["costing"]!!.jsonPrimitive.content)
            assertEquals("true", body["banner_instructions"]!!.jsonPrimitive.content)
            assertEquals("true", body["voice_instructions"]!!.jsonPrimitive.content)
            assertEquals("miles", body["units"]!!.jsonPrimitive.content)
            val locations = body["locations"]!!.jsonArray
            assertEquals(4, locations.size)
            assertNull(locations[0].jsonObject["type"])
            assertTrue(locations.drop(1).all { it.jsonObject["type"]!!.jsonPrimitive.content == "break" })
            assertEquals("0.0", body["costing_options"]!!.jsonObject["auto"]!!.jsonObject["use_tolls"]!!.jsonPrimitive.content)

            // The options handed to Ferrostar for recalculation carry the same settings.
            val opts = Json.parseToJsonElement(result.legs[0].optionsJson).jsonObject
            assertTrue("costing_options" in opts && "units" in opts && "locations" !in opts)
        }
    }

    @Test
    fun cyclingPrefersBikePaths() {
        val opts = ValhallaRouteProvider.requestOptions("bicycle", RouteOptions(), withExclusions = true)
        assertEquals("0.1", opts["costing_options"]!!.jsonObject["bicycle"]!!.jsonObject["use_roads"]!!.jsonPrimitive.content)
    }

    @Test
    fun longTripsAreChunkedToTheServerLimit() {
        val pts = (0 until 11).toList()
        assertEquals(listOf((0..9).toList(), listOf(9, 10)), ValhallaRouteProvider.chunk(pts, 10))
        assertEquals(listOf(pts), ValhallaRouteProvider.chunk(pts, 11))
        val chunks = ValhallaRouteProvider.chunk((0 until 21).toList(), 10)
        assertEquals(listOf(0, 9, 18), chunks.map { it.first() })
        assertEquals(20, chunks.sumOf { it.size - 1 })
    }

    @Test
    fun perimeterLimitFallsBackToARouteWithoutExclusions() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(400).body("""{"code":"PerimeterExceeded","message":"Perimeter of avoid polygons exceeds the max limit."}""").build())
            server.enqueue(MockResponse.Builder().code(200).body(fixture("valhalla_auto_3stops.json")).build())
            server.start()
            val provider = ValhallaRouteProvider(ValhallaRouteProvider.Endpoint(server.url("/route").toString(), 10, "test"))
            val zone = listOf(LatLng(51.51, -0.15), LatLng(51.52, -0.08), LatLng(51.49, -0.09))
            val result = provider.route(RouteRequest(TravelMode.DRIVE, listOf(chelsea) + stops, RouteOptions(excludePolygons = listOf(zone))))
            assertTrue(result.exclusionsIgnored)
            val first = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
            val ring = first["exclude_polygons"]!!.jsonArray[0].jsonArray
            assertEquals(4, ring.size) // closed ring
            assertEquals("-0.15", ring[0].jsonArray[0].jsonPrimitive.content) // lon first
            val second = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
            assertNull(second["exclude_polygons"])
        }
    }

    @Test
    fun routingErrorsBecomeReadableMessages() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(400).body("""{"code":"NoRoute","message":"No path could be found for input"}""").build())
            server.start()
            val provider = ValhallaRouteProvider(ValhallaRouteProvider.Endpoint(server.url("/route").toString(), 10, "Test router"))
            val e = assertFailsWith<ProviderException> { provider.route(RouteRequest(TravelMode.WALK, listOf(chelsea, stops[0]))) }
            assertEquals("Test router: No path could be found for input", e.message)
        }
    }

    @Test
    fun photonResultsHaveLabelsAndAddresses() {
        val places = PhotonPlaceProvider.parse(Json.parseToJsonElement(fixture("photon_search.json")))
        assertEquals(5, places.size)
        val museum = places.first()
        assertEquals("British Museum", museum.label)
        assertEquals("R177044", museum.osmId)
        assertTrue(museum.address!!.contains("Great Russell Street"))
        assertEquals(51.519, museum.latLng.lat, 0.01)
    }

    @Test
    fun tflJourneysMapToSegments() {
        val its = TflClient.parseJourneys(Json.parseToJsonElement(fixture("tfl_journey.json")))
        assertEquals(3, its.size)
        val first = its.first()
        // 09:04 London time in November is 09:04 UTC.
        assertEquals(Instant.parse("2026-11-24T09:04:00Z"), first.departAt)
        assertEquals(Instant.parse("2026-11-24T09:47:00Z"), first.arriveAt)
        val kinds = first.segments.map { if (it is Segment.TransitRide) "${it.vehicle}:${it.line}" else "walk" }
        assertEquals(listOf("walk", "BUS:10", "BUS:14", "walk"), kinds)
        val bus = first.segments[1] as Segment.TransitRide
        assertEquals("Newington Green", bus.headsign)
        assertEquals("490005073KL", bus.board.id)
        assertTrue(bus.geometry.size > 5)
        assertEquals(setOf(TransitVehicle.BUS), first.vehicles)
        assertEquals(TransitVehicle.SUBWAY, TflClient.vehicleFor("tube"))
        assertEquals(TransitVehicle.TRAIN, TflClient.vehicleFor("elizabeth-line"))
    }

    @Test
    fun transitousItinerariesMapToSegments() {
        val its = TransitousTransitProvider.parse(Json.parseToJsonElement(fixture("transitous_plan.json")))
        assertTrue(its.isNotEmpty())
        val first = its.first()
        assertEquals(Instant.parse("2026-11-24T09:00:00Z"), first.departAt)
        val rides = first.segments.filterIsInstance<Segment.TransitRide>()
        assertEquals(listOf("345", "Piccadilly"), rides.map { it.line })
        assertEquals(listOf(TransitVehicle.BUS, TransitVehicle.SUBWAY), rides.map { it.vehicle })
        assertTrue(rides[0].intermediateStops.isNotEmpty())
        assertTrue(first.segments.first() is Segment.Walk)
    }


}
