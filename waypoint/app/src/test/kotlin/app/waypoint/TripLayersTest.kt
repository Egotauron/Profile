package app.waypoint

import app.waypoint.trip.Plan
import app.waypoint.ui.LineKind
import app.waypoint.ui.legArrival
import app.waypoint.ui.lineCollections
import app.waypoint.ui.nextStep
import app.waypoint.ui.transitStopsGeoJson
import app.waypoint.trip.Segment
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.junit.Test

/** What the navigation map draws beyond the step being guided, on the simulated London day. */
class TripLayersTest {
    private val plan: Plan = Json { ignoreUnknownKeys = true }
        .decodeFromString(Plan.serializer(), javaClass.getResource("/sim_plan.json")!!.readText())
    private val transit = plan.legs.indexOfFirst { l -> l.segments.any { it is Segment.TransitRide } }
    private val leg = plan.legs[transit]

    private fun empty(geoJson: String) = !geoJson.contains("\"coordinates\"")

    @Test
    fun walkingToTheStopShowsTheBusesAndTheRestOfTheTrip() {
        val tiers = lineCollections(plan, fromLeg = transit, afterSegment = 0, currentLeg = transit)
        val now = tiers.getValue(0)
        assertTrue(!empty(now.getValue(LineKind.BUS)), "the buses of this leg are drawn")
        assertTrue(!empty(now.getValue(LineKind.WALK)), "the walk from the last bus is drawn")
        assertTrue(empty(now.getValue(LineKind.DRIVE)), "earlier legs are left out")
        val later = tiers.getValue(1)
        assertTrue(!empty(later.getValue(LineKind.CYCLE)) && !empty(later.getValue(LineKind.TUBE)), "later legs are drawn, fainter")
        val stops = transitStopsGeoJson(plan, transit, 0)
        assertEquals(2 * plan.legs.drop(transit).sumOf { l -> l.segments.count { it is Segment.TransitRide } }, Regex("\"Point\"").findAll(stops).count())
    }

    @Test
    fun onTheLastWalkOnlyLaterLegsRemain() {
        val now = lineCollections(plan, fromLeg = transit, afterSegment = leg.segments.lastIndex, currentLeg = transit).getValue(0)
        assertTrue(now.values.all(::empty))
    }

    @Test
    fun arrivalWaitsForTheBus() {
        val firstRide = leg.segments.filterIsInstance<Segment.TransitRide>().first()
        // Reaching the bus stop early doesn't get you there sooner than the timetable.
        val early = legArrival(leg, 0, firstRide.departAt - kotlin.time.Duration.parse("10m"))
        assertEquals(leg.arriveAt, early)
        assertTrue(nextStep(leg, 0, "British Museum")!!.startsWith("Next: bus ${firstRide.line} towards"))
        assertTrue(nextStep(leg, leg.segments.lastIndex, "British Museum") == null)
    }
}
