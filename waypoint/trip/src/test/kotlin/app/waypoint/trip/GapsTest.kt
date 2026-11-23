package app.waypoint.trip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate

class GapsTest {
    @Test
    fun englandBankHolidays() {
        assertEquals(
            setOf("2026-01-01", "2026-04-03", "2026-04-06", "2026-05-04", "2026-05-25", "2026-08-31", "2026-12-25", "2026-12-28"),
            BankHolidays.englandAndWales(2026).map { it.toString() }.toSet(),
        )
        // 2027: Christmas on a Saturday, Boxing Day on a Sunday.
        assertTrue(LocalDate(2027, 12, 27) in BankHolidays.englandAndWales(2027))
        assertTrue(LocalDate(2027, 12, 28) in BankHolidays.englandAndWales(2027))
        // 2022: Christmas on a Sunday.
        assertEquals(setOf(LocalDate(2022, 12, 26), LocalDate(2022, 12, 27)), BankHolidays.englandAndWales(2022).filter { it.monthNumber == 12 }.toSet())
        assertEquals(LocalDate(2027, 3, 28), BankHolidays.easterSunday(2027))
    }

    @Test
    fun congestionChargeUsesWeekendHoursOnBankHolidays() {
        // Easter Monday 2026, 10:00 and 13:00 BST.
        assertFalse(London.isChargingTime(Instant.parse("2026-04-06T09:00:00Z")))
        assertTrue(London.isChargingTime(Instant.parse("2026-04-06T12:00:00Z")))
        // 1 Jan 2028 is a Saturday: the holiday is Monday 3 Jan, still free; Tuesday is charged.
        assertFalse(London.isChargingTime(Instant.parse("2028-01-03T10:00:00Z")))
        assertTrue(London.isChargingTime(Instant.parse("2028-01-04T10:00:00Z")))
    }

    @Test
    fun ulezWarningOnlyForNonCompliantCarsInLondon() = runTest {
        val chelsea = LatLng(51.4869, -0.1696)
        val clapham = Place(null, LatLng(51.4618, -0.1384), "Clapham")
        val trip = Trip(id = "t", stops = listOf(Stop("1", clapham)), defaultMode = ModeChoice.Fixed(TravelMode.DRIVE))
        fun planner(compliant: Boolean) = TripPlanner(FakeRoutes(), null, settings = { PlannerSettings(ulezCompliantCar = compliant) })
        val saturday = Instant.parse("2026-11-28T10:00:00Z")
        assertFalse(Warning.ULEZ in planner(true).plan(trip, chelsea, saturday).legs.single().warnings)
        assertTrue(Warning.ULEZ in planner(false).plan(trip, chelsea, saturday).legs.single().warnings)
        assertFalse(Warning.ULEZ in planner(false).plan(trip, chelsea, Instant.parse("2026-12-25T10:00:00Z")).legs.single().warnings)
        // Outside London: no ULEZ.
        val oxford = Trip(id = "o", stops = listOf(Stop("1", Place(null, LatLng(51.7600, -1.2100), "Headington"))), defaultMode = ModeChoice.Fixed(TravelMode.DRIVE))
        assertFalse(Warning.ULEZ in planner(false).plan(oxford, LatLng(51.7520, -1.2577), saturday).legs.single().warnings)
    }

    /** Buses every 15 minutes: you board at the next quarter-hour after a 3-minute walk. */
    private class QuarterHourBus : TransitProvider {
        override suspend fun plan(from: LatLng, to: LatLng, departAt: Instant): List<TransitItinerary> {
            val atStop = departAt + 3.minutes
            val q = 15 * 60
            val board = Instant.fromEpochSeconds(((atStop.epochSeconds + q - 1) / q) * q)
            val alight = board + 10.minutes
            return listOf(
                TransitItinerary(
                    departAt, alight + 3.minutes,
                    listOf(
                        Segment.Walk(null, 3.minutes, from, from),
                        Segment.TransitRide("14", TransitVehicle.BUS, "Putney", TransitStop("A", from), TransitStop("B", to), board, alight),
                        Segment.Walk(null, 3.minutes, to, to),
                    ),
                ),
            )
        }
    }

    @Test
    fun arriveByFindsTheLatestBusThatMakesIt() = runTest {
        val now = Instant.parse("2026-11-29T09:00:00Z")
        val target = Instant.parse("2026-11-29T10:30:00Z")
        val trip = Trip(
            id = "t",
            time = TimeConstraint.ArriveBy(target),
            stops = listOf(Stop("1", Place(null, LatLng(51.7600, -1.2100), "Headington"))),
            defaultMode = ModeChoice.Fixed(TravelMode.TRANSIT),
        )
        val planner = TripPlanner(FakeRoutes(), QuarterHourBus())
        val plan = planner.plan(trip, LatLng(51.7520, -1.2577), now)
        // Leaving now you'd arrive 09:28. The last bus that makes 10:30 leaves at 10:15, so you can
        // leave as late as 10:12. "Arrival minus slack" alone would say 10:02.
        val latest = plan.latestDeparture!!
        assertTrue(latest > Instant.parse("2026-11-29T10:02:00Z"), "latest $latest")
        assertTrue(latest <= Instant.parse("2026-11-29T10:12:00Z"), "latest $latest")
        val check = planner.plan(trip.copy(time = TimeConstraint.DepartAt(latest)), LatLng(51.7520, -1.2577), now)
        assertTrue(check.arriveAt <= target)
    }

    @Test
    fun osmAndGetsEveryRemainingStop() {
        val stops = listOf(OpenIn.Target(LatLng(51.5, -0.1), "Tate & Lyle"), OpenIn.Target(LatLng(51.51, -0.12), "B"))
        val uri = OpenIn.osmAndAllStops(stops, TravelMode.DRIVE)
        assertTrue(uri.startsWith("osmand.api://navigate_gpx?gpx="))
        val gpx = java.net.URLDecoder.decode(uri.substringAfter("gpx=").substringBefore("&force"), "UTF-8")
        assertEquals(2, Regex("<rtept ").findAll(gpx).count())
        assertTrue("Tate &amp; Lyle" in gpx)
        assertTrue(OpenIn.osmAndAllStops(stops.take(1), TravelMode.WALK).startsWith("osmand.api://navigate?"))
    }
}
