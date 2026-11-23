package app.waypoint.trip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

class PlannerTest {
    // Oxford: outside London, so the drive-time factor is 1.0 and the arithmetic stays readable.
    private val home = LatLng(51.7520, -1.2577)
    private val a = place("Covered Market", 51.7524, -1.2566)
    private val b = place("Ashmolean", 51.7554, -1.2600)
    private val c = place("University Parks", 51.7600, -1.2500)
    private val d = place("Headington", 51.7600, -1.2100)
    private val sunday = Instant.parse("2026-11-29T15:00:00Z")

    private fun planner(
        routes: RouteProvider = FakeRoutes(),
        transit: TransitProvider? = FakeTransit(),
        bikes: BikeShareProvider? = null,
        settings: PlannerSettings = PlannerSettings(),
    ) = TripPlanner(routes, transit, bikes, { DriveTimeCalibration() }, { settings })

    @Test
    fun consecutiveFixedLegsOfOneModeShareOneRequest() = runTest {
        val routes = FakeRoutes()
        val trip = Trip(
            id = "t",
            stops = listOf(
                stop("1", a), stop("2", b), stop("3", c),
                stop("4", d) { copy(modeToHere = ModeChoice.Fixed(TravelMode.WALK)) },
            ),
            defaultMode = ModeChoice.Fixed(TravelMode.DRIVE),
        )
        val plan = planner(routes).plan(trip, home, sunday)
        assertEquals(4, plan.legs.size)
        assertEquals(2, routes.requests.size)
        val drive = routes.requests.single { it.mode == TravelMode.DRIVE }
        assertEquals(listOf(home, a.latLng, b.latLng, c.latLng), drive.points)
        assertEquals(listOf(c.latLng, d.latLng), routes.requests.single { it.mode == TravelMode.WALK }.points)
        assertEquals(listOf(TravelMode.DRIVE, TravelMode.DRIVE, TravelMode.DRIVE, TravelMode.WALK), plan.legs.map { it.mode })
        assertEquals(listOf(null, "1", "2", "3"), plan.legs.map { it.fromStopId })
    }

    @Test
    fun everyLegDepartsAtPreviousArrivalPlusStayAndDrivesAddParking() = runTest {
        val trip = Trip(
            id = "t",
            stops = listOf(
                stop("1", a) { copy(stay = 30.minutes, parkingTime = 7.minutes) },
                stop("2", b),
            ),
            defaultMode = ModeChoice.Fixed(TravelMode.DRIVE),
            defaultStay = 10.minutes,
        )
        val plan = planner().plan(trip, home, sunday)
        val (l1, l2) = plan.legs
        assertEquals(sunday, l1.departAt)
        val drive1 = l1.segments[0] as Segment.Drive
        assertEquals(drive1.rawDuration, drive1.adjustedDuration) // factor 1.0 outside London
        assertEquals(Segment.Park(7.minutes), l1.segments[1])
        assertEquals(l1.departAt + drive1.adjustedDuration + 7.minutes, l1.arriveAt)
        assertEquals(l1.arriveAt + 30.minutes, l2.departAt)
        // Stop 2 has no parking time of its own: the settings default (5 min) applies.
        assertEquals(Segment.Park(5.minutes), l2.segments[1])
        assertEquals(l2.arriveAt, plan.arriveAt)
        assertTrue(Warning.NO_LIVE_TRAFFIC in l1.warnings)
    }

    @Test
    fun driveToACarParkStartsTheNextLegFromThere() = runTest {
        val carPark = LatLng(51.7530, -1.2550)
        val routes = FakeRoutes()
        val trip = Trip(
            id = "t",
            stops = listOf(
                stop("1", a) { copy(arrivalPoint = carPark, modeToHere = ModeChoice.Fixed(TravelMode.DRIVE)) },
                stop("2", b) { copy(modeToHere = ModeChoice.Fixed(TravelMode.WALK)) },
            ),
        )
        planner(routes).plan(trip, home, sunday)
        assertEquals(listOf(home, carPark), routes.requests.single { it.mode == TravelMode.DRIVE }.points)
        assertEquals(listOf(carPark, b.latLng), routes.requests.single { it.mode == TravelMode.WALK }.points)
    }

    @Test
    fun fastestPicksTheEarliestArrivalAndKeepsTheRunnerUp() = runTest {
        val trip = Trip(
            id = "t",
            stops = listOf(stop("1", d)), // ~3.2 km: walking ~38 min, the tube ~18 min
            defaultMode = ModeChoice.Fastest(FastestOptions()),
        )
        val leg = planner().plan(trip, home, sunday).legs.single()
        assertEquals(TravelMode.TRANSIT, leg.mode)
        assertTrue(leg.chosenByFastest)
        assertEquals(TravelMode.WALK, leg.runnerUp?.mode)
        assertTrue(leg.runnerUp!!.arriveAt > leg.arriveAt)
    }

    @Test
    fun transitIsFilteredToTheAllowedVehicles() = runTest {
        val trip = Trip(
            id = "t",
            stops = listOf(stop("1", d)),
            defaultMode = ModeChoice.Fixed(TravelMode.TRANSIT, transit = setOf(TransitVehicle.BUS)),
        )
        val leg = planner(transit = FakeTransit(listOf(TransitVehicle.SUBWAY))).plan(trip, home, sunday).legs.single()
        assertEquals(TravelMode.WALK, leg.mode)
        assertTrue(Warning.NO_TRANSIT_MATCH in leg.warnings)

        val bus = planner(transit = FakeTransit(listOf(TransitVehicle.SUBWAY, TransitVehicle.BUS))).plan(trip, home, sunday).legs.single()
        assertEquals(TravelMode.TRANSIT, bus.mode)
        assertEquals("14", bus.segments.filterIsInstance<Segment.TransitRide>().single().line)
    }

    @Test
    fun youStayOnYourCarUntilYouParkIt() = runTest {
        val near = place("Next door", 51.7605, -1.2105) // a 1-minute walk from d
        val back = place("Headington again", 51.7601, -1.2101)
        val options = FastestOptions(drive = true, transit = emptySet())
        val trip = Trip(
            id = "t",
            stops = listOf(
                stop("1", d),
                stop("2", near) { copy(leaveVehicle = true) },
                stop("3", c),
            ),
            defaultMode = ModeChoice.Fastest(options),
        )
        val plan = planner().plan(trip, home, sunday)
        // Leg 1: the car is fastest. Leg 2: walking would win, but you have the car with you.
        assertEquals(TravelMode.DRIVE, plan.legs[0].mode)
        assertEquals(TravelMode.DRIVE, plan.legs[1].mode)
        assertNull(plan.legs[1].runnerUp)
        // Leg 3: the car is parked at stop 2, so you walk, and the trip never comes back to it.
        assertEquals(TravelMode.WALK, plan.legs[2].mode)
        assertTrue(Warning.VEHICLE_LEFT_BEHIND in plan.legs[2].warnings)

        // Coming back to the car clears the warning.
        val roundTrip = trip.copy(stops = trip.stops + stop("4", back))
        val plan2 = planner().plan(roundTrip, home, sunday)
        assertFalse(plan2.legs.any { Warning.VEHICLE_LEFT_BEHIND in it.warnings })
    }

    @Test
    fun arriveByReportsLatenessAndTheLatestDeparture() = runTest {
        val base = Trip(id = "t", stops = listOf(stop("1", d)), defaultMode = ModeChoice.Fixed(TravelMode.WALK))
        val late = planner().plan(base.copy(time = TimeConstraint.ArriveBy(sunday + 10.minutes)), home, sunday)
        assertTrue(Warning.LATE_FOR_ARRIVE_BY in late.allWarnings)
        assertTrue(late.arriveBySlack!!.isNegative())

        val early = planner().plan(base.copy(time = TimeConstraint.ArriveBy(sunday + 120.minutes)), home, sunday)
        assertFalse(Warning.LATE_FOR_ARRIVE_BY in early.allWarnings)
        assertEquals(sunday + early.arriveBySlack!!, early.latestDeparture)
        assertEquals(sunday + 120.minutes, early.latestDeparture!! + (early.arriveAt - early.departAt))
    }

    @Test
    fun chargeZoneIsFlaggedAndAvoidanceRequested() = runTest {
        val chelsea = LatLng(51.4869, -0.1696)
        val shoreditch = place("Shoreditch", 51.5265, -0.0780)
        val tuesday = Instant.parse("2026-11-24T10:00:00Z")
        val routes = FakeRoutes(ignoreExclusions = true)
        val trip = Trip(id = "t", stops = listOf(stop("1", shoreditch)), defaultMode = ModeChoice.Fixed(TravelMode.DRIVE))
        val plain = planner(routes).plan(trip, chelsea, tuesday).legs.single()
        assertTrue(Warning.CHARGE_ZONE in plain.warnings)
        assertTrue(routes.requests.last().options.excludePolygons.isEmpty())

        val avoid = planner(routes, settings = PlannerSettings(avoidChargeZone = true)).plan(trip, chelsea, tuesday).legs.single()
        assertEquals(London.congestionChargeZone, routes.requests.last().options.excludePolygons.single())
        // The fake router (like the public server) can't avoid it: say so.
        assertTrue(Warning.CHARGE_ZONE_NOT_AVOIDED in avoid.warnings)
        // Evening: no charge.
        val evening = planner(routes).plan(trip, chelsea, Instant.parse("2026-11-24T19:30:00Z")).legs.single()
        assertFalse(Warning.CHARGE_ZONE in evening.warnings)
    }

    @Test
    fun bikeShareLegWalksRidesAndWalks() = runTest {
        val docks = listOf(
            Dock("p1", "Pickup", LatLng(51.7525, -1.2570), bikesAvailable = 3, spacesAvailable = 5, system = "test"),
            Dock("p0", "Empty", LatLng(51.7521, -1.2576), bikesAvailable = 0, spacesAvailable = 9, system = "test"),
            Dock("d1", "Dropoff", LatLng(51.7598, -1.2110), bikesAvailable = 0, spacesAvailable = 4, system = "test"),
        )
        val trip = Trip(id = "t", stops = listOf(stop("1", d)), defaultMode = ModeChoice.Fixed(TravelMode.BIKE_SHARE))
        val leg = planner(bikes = FakeBikes(docks)).plan(trip, home, sunday).legs.single()
        assertEquals(TravelMode.BIKE_SHARE, leg.mode)
        val kinds = leg.segments.map { it::class.simpleName }
        assertEquals(listOf("Walk", "BikePickup", "Cycle", "BikeDropoff", "Walk"), kinds)
        assertEquals("p1", (leg.segments[1] as Segment.BikePickup).dock.id)
        assertEquals(leg.departAt + leg.segments.fold(Duration.ZERO) { acc, s -> acc + s.duration }, leg.arriveAt)

        val none = planner(bikes = FakeBikes(emptyList())).plan(trip, home, sunday).legs.single()
        assertEquals(TravelMode.WALK, none.mode)
        assertTrue(Warning.NO_BIKE_DOCKS in none.warnings)
    }

    @Test
    fun retimeReplansTransitAndShiftsTheRest() = runTest {
        val transit = FakeTransit()
        val trip = Trip(
            id = "t",
            stops = listOf(
                stop("1", a) { copy(modeToHere = ModeChoice.Fixed(TravelMode.WALK), stay = 15.minutes) },
                stop("2", d) { copy(modeToHere = ModeChoice.Fixed(TravelMode.TRANSIT)) },
            ),
        )
        val p = planner(transit = transit)
        val plan = p.plan(trip, home, sunday)
        val later = plan.legs[1].departAt + 20.minutes
        val retimed = p.retime(plan, fromLeg = 1, departAt = later, now = sunday)
        assertEquals(later, transit.requests.last())
        assertEquals(later, retimed.legs[1].departAt)
        assertEquals(plan.legs[0], retimed.legs[0])
        assertEquals(plan.legs[1].arriveAt + 20.minutes, retimed.arriveAt)
    }

    @Test
    fun skippingTheNextStopPlansOnwardsFromHere() = runTest {
        val routes = FakeRoutes()
        val trip = Trip(
            id = "t",
            stops = listOf(stop("1", a), stop("2", b), stop("3", c)),
            defaultMode = ModeChoice.Fixed(TravelMode.WALK),
        )
        val p = planner(routes)
        val plan = p.plan(trip, home, sunday)
        val now = plan.legs[0].arriveAt + 5.minutes
        val skipped = p.replanFrom(plan, atStopIndex = 0, position = a.latLng, now = now, dropStopId = "2")
        assertEquals(listOf("1", "3"), skipped.trip.stops.map { it.id })
        assertEquals(2, skipped.legs.size)
        assertEquals(plan.legs[0], skipped.legs[0])
        assertEquals("1", skipped.legs[1].fromStopId)
        assertEquals("3", skipped.legs[1].toStopId)
        assertEquals(now, skipped.legs[1].departAt)
        assertEquals(listOf(a.latLng, c.latLng), routes.requests.last().points)
    }

    @Test
    fun noModesForFastestIsAClearError() = runTest {
        val trip = Trip(
            id = "t",
            stops = listOf(stop("1", d)),
            defaultMode = ModeChoice.Fastest(FastestOptions(walk = false, transit = emptySet())),
        )
        val e = runCatching { planner().plan(trip, home, sunday) }.exceptionOrNull()
        assertNotNull(e)
        assertTrue(e is PlanningException && e.message!!.contains("Headington"))
    }

    @Test
    fun tripsSerializeForSaving() {
        val trip = Trip(
            id = "t",
            name = "Saturday errands",
            origin = Origin.Fixed(place("Home", 51.4869, -0.1696)),
            time = TimeConstraint.ArriveBy(sunday),
            stops = listOf(stop("1", a) { copy(continuation = Continuation.Manual, stay = 15.minutes, leaveVehicle = true) }),
            defaultMode = ModeChoice.Fastest(FastestOptions(drive = true, avoidChargeZone = true)),
            defaultStay = 5.seconds,
        )
        val json = kotlinx.serialization.json.Json.encodeToString(Trip.serializer(), trip)
        assertEquals(trip, kotlinx.serialization.json.Json.decodeFromString(Trip.serializer(), json))
    }
}
