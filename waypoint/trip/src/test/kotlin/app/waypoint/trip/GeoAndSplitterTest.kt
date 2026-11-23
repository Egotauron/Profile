package app.waypoint.trip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class GeoAndSplitterTest {
    private fun fixture(name: String) = javaClass.getResource("/$name")!!.readText()

    @Test
    fun polylineRoundTrips() {
        val pts = listOf(LatLng(51.487076, -0.169771), LatLng(51.4994, -0.1245), LatLng(51.5081, -0.0759), LatLng(51.0, 0.000051))
        val decoded = Polyline.decode(Polyline.encode(pts))
        assertEquals(pts.size, decoded.size)
        pts.zip(decoded).forEach { (a, b) ->
            assertEquals(a.lat, b.lat, 1e-6)
            assertEquals(a.lng, b.lng, 1e-6)
        }
    }

    @Test
    fun distanceIsSane() {
        // Chelsea Old Town Hall to the British Museum: about 5.0 km in a straight line.
        val d = Geo.distanceMeters(LatLng(51.4869, -0.1696), LatLng(51.5194, -0.1270))
        assertTrue(d in 4600.0..5000.0, "got $d")
    }

    @Test
    fun chargeZoneContainsTrafalgarSquareButNotChelsea() {
        assertTrue(London.inChargeZone(LatLng(51.5080, -0.1281)))
        assertTrue(London.inChargeZone(LatLng(51.5138, -0.0984))) // St Paul's
        assertFalse(London.inChargeZone(LatLng(51.4869, -0.1696))) // King's Road
        assertFalse(London.inChargeZone(LatLng(51.5307, -0.1238).let { LatLng(it.lat + 0.01, it.lng) })) // north of King's Cross
        assertTrue(London.inGreaterLondon(LatLng(51.4869, -0.1696)))
        assertFalse(London.inGreaterLondon(LatLng(51.7520, -1.2577))) // Oxford
    }

    @Test
    fun splitterProducesOneRoutePerLegMatchingTheResponse() {
        val raw = fixture("valhalla_auto_3stops.json")
        val legs = OsrmSplitter.split(raw)
        val root = Json.parseToJsonElement(raw).jsonObject
        val route = root["routes"]!!.jsonArray[0].jsonObject
        val srcLegs = route["legs"]!!.jsonArray
        assertEquals(3, legs.size)

        // Durations and distances are the original legs'.
        legs.zip(srcLegs).forEach { (mine, src) ->
            assertEquals(src.jsonObject["duration"]!!.jsonPrimitive.double, mine.durationSeconds, 1e-9)
            assertEquals(src.jsonObject["distance"]!!.jsonPrimitive.double, mine.distanceMeters, 1e-9)
        }
        // Each leg ends where the next begins, at the response's waypoints.
        val wps = root["waypoints"]!!.jsonArray.map {
            val loc = it.jsonObject["location"]!!.jsonArray
            LatLng(loc[1].jsonPrimitive.double, loc[0].jsonPrimitive.double)
        }
        legs.forEachIndexed { i, leg ->
            assertTrue(Geo.distanceMeters(leg.geometry.first(), wps[i]) < 5.0, "leg $i start")
            assertTrue(Geo.distanceMeters(leg.geometry.last(), wps[i + 1]) < 5.0, "leg $i end")
        }
        // The legs together cover the original line.
        val full = Polyline.decode(route["geometry"]!!.jsonPrimitive.content)
        val joined = Geo.lengthMeters(legs.flatMap { it.geometry })
        assertEquals(Geo.lengthMeters(full), joined, 25.0)

        // Each single-leg route is a valid OSRM route object for Ferrostar: one leg, two waypoints.
        val first = Json.parseToJsonElement(legs[0].routeJson).jsonObject
        assertEquals(1, first["legs"]!!.jsonArray.size)
        assertEquals(2, Json.parseToJsonElement(legs[0].waypointsJson).jsonArray.size)
        assertEquals(legs[0].geometry.size, Polyline.decode(first["geometry"]!!.jsonPrimitive.content).size)
        // Leave the legs where the Ferrostar parser check (tools/ferrostar-check) can read them.
        java.io.File("build/split-legs").apply { mkdirs() }.let { dir ->
            legs.forEachIndexed { i, leg ->
                java.io.File(dir, "leg$i.route.json").writeText(leg.routeJson)
                java.io.File(dir, "leg$i.waypoints.json").writeText(leg.waypointsJson)
            }
        }
        val steps = first["legs"]!!.jsonArray[0].jsonObject["steps"]!!.jsonArray
        assertEquals("depart", steps.first().jsonObject["maneuver"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("arrive", steps.last().jsonObject["maneuver"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun splitterReportsRoutingErrors() {
        val e = assertFailsWith<OsrmSplitter.OsrmException> {
            OsrmSplitter.split("""{"code":"PerimeterExceeded","message":"Perimeter of avoid polygons exceeds the max limit."}""")
        }
        assertTrue(e.message!!.startsWith("PerimeterExceeded"))
    }

    @Test
    fun resampleKeepsEnds() {
        val line = listOf(LatLng(51.5, -0.1), LatLng(51.51, -0.1))
        val r = Geo.resample(line, 100.0)
        assertEquals(line.first(), r.first())
        assertEquals(line.last(), r.last())
        assertTrue(r.size in 11..13)
    }
}
