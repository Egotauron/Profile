package app.waypoint.providers

import app.waypoint.trip.LatLng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A real Valhalla walk (Henniker Mews to King's Road, footpaths preferred): the last 200 m are the
 * pavement of Park Walk, unnamed, so it said "Turn left onto the walkway".
 */
class WalkwayNamesTest {
    private val osrm = javaClass.getResource("/fixtures/valhalla_walk_park_walk.json")!!.readText()

    private fun steps(json: String) = Json.parseToJsonElement(json).jsonObject["routes"]!!.jsonArray[0].jsonObject["legs"]!!
        .jsonArray[0].jsonObject["steps"]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.voice() = this["voiceInstructions"]!!.jsonArray.map { it.jsonObject["announcement"]!!.jsonPrimitive.content }
    private fun JsonObject.ssml() = this["voiceInstructions"]!!.jsonArray.map { it.jsonObject["ssmlAnnouncement"]!!.jsonPrimitive.content }
    private fun JsonObject.banner() = this["bannerInstructions"]!!.jsonArray.map { it.jsonObject["primary"]!!.jsonObject["text"]!!.jsonPrimitive.content }
    private fun JsonObject.instruction() = this["maneuver"]!!.jsonObject["instruction"]!!.jsonPrimitive.content

    @Test
    fun thePavementIsCalledByItsStreet() = runTest {
        val asked = mutableListOf<List<LatLng>>()
        // What Valhalla's locate answered for the middle of the 200 m step: Park Walk, 4 m away, running alongside.
        val named = steps(WalkwayNames.name(osrm) { pts -> asked += pts; pts.map { listOf(WalkwayNames.Road("Park Walk", 4.0, 140.2)) } })
        assertEquals(1, asked.single().size, "one lookup, for the one long unnamed step (the 5 m link is a crossing)")
        assertEquals("Park Walk", named[5]["name"]!!.jsonPrimitive.content)
        assertEquals("Turn left onto Park Walk.", named[5].instruction())
        assertEquals(listOf("Turn left onto Park Walk."), named[4].voice(), "the voice announcing it")
        assertEquals(listOf("Turn left onto Park Walk."), named[4].banner())
        assertTrue(named[4].ssml().single().contains("Turn left onto Park Walk."))
        assertEquals("Turn right onto the walkway. Then Turn left onto Park Walk.", named[3].voice().single(), "the crossing stays a walkway")
        assertEquals("Turn right onto the walkway.", named[4].instruction())
        assertEquals("Turn left onto Callow Street.", named[1].instruction(), "named streets untouched")
    }

    @Test
    fun aRoadAcrossTheStepOrFarAwayDoesNotNameIt() = runTest {
        for (road in listOf(WalkwayNames.Road("Across", 4.0, 230.0), WalkwayNames.Road("Far", 35.0, 140.0))) {
            val out = WalkwayNames.name(osrm) { pts -> pts.map { listOf(road) } }
            assertEquals(osrm, out)
        }
    }

    @Test
    fun aRoadPointingTheOtherWayStillCounts() = runTest {
        val named = steps(WalkwayNames.name(osrm) { pts -> pts.map { listOf(WalkwayNames.Road("Park Walk", 4.0, 320.0)) } })
        assertEquals("Park Walk", named[5]["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun locateResponsesAreRead() {
        val roads = WalkwayNames.parseLocate(Json.parseToJsonElement(
            """[{"edges":[{"distance":4.0,"heading":140.2,"edge_info":{"names":["Park Walk"]}},{"distance":9,"heading":3,"edge_info":{}}]},{"edges":null}]""",
        ))
        assertEquals(listOf(listOf(WalkwayNames.Road("Park Walk", 4.0, 140.2)), emptyList()), roads)
    }
}
