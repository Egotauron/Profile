package app.waypoint.providers

import app.waypoint.trip.Continuation
import app.waypoint.trip.DriveTimeCalibration
import app.waypoint.trip.FastestOptions
import app.waypoint.trip.LatLng
import app.waypoint.trip.Leg
import app.waypoint.trip.London
import app.waypoint.trip.ModeChoice
import app.waypoint.trip.NavRoute
import app.waypoint.trip.Place
import app.waypoint.trip.Plan
import app.waypoint.trip.PlannerSettings
import app.waypoint.trip.RuntimeEffect
import app.waypoint.trip.Segment
import app.waypoint.trip.Stop
import app.waypoint.trip.StopWait
import app.waypoint.trip.TimeConstraint
import app.waypoint.trip.TravelMode
import app.waypoint.trip.Trip
import app.waypoint.trip.TripPhase
import app.waypoint.trip.TripPlanner
import app.waypoint.trip.TripRuntime
import app.waypoint.trip.Warning
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue

/**
 * A whole day out in London, planned with the real services and played forward on a simulated
 * clock through the same [TripRuntime] the app uses. Run with:
 *
 *   ./gradlew :providers:test --tests '*TripSimulationTest*' -Pwaypoint.live=true
 *
 * Writes a readable log to build/simulation/report.txt, the plan to build/simulation/plan.json
 * (used by the app's screenshot tests) and every guided route to build/simulation/routes/ for the
 * Ferrostar check in tools/ferrostar-check.
 */
class TripSimulationTest {
    private val out = File("build/simulation").apply { mkdirs() }
    private val log = StringBuilder()

    private fun clock(t: Instant): String = t.toLocalDateTime(London.timeZone).let {
        "%02d:%02d".format(it.hour, it.minute)
    }

    private fun say(t: Instant, text: String) {
        val line = "${clock(t)}  $text"
        println(line)
        log.appendLine(line)
    }

    private fun mins(d: Duration) = "${(d.inWholeSeconds + 30) / 60} min"

    private fun place(label: String, lat: Double, lng: Double) = Place(null, LatLng(lat, lng), label)

    private val victoria = LatLng(51.4952, -0.1441)
    private val queensGate = LatLng(51.4975, -0.1795) // a street near the museum, for the car

    private val trip = Trip(
        id = "sim",
        name = "Saturday in London",
        origin = app.waypoint.trip.Origin.Fixed(Place(null, victoria, "Victoria")),
        // Next Saturday, 10:00 London time.
        time = TimeConstraint.DepartAt(Instant.parse("2026-11-28T10:00:00Z")),
        stops = listOf(
            Stop("1", place("Natural History Museum", 51.4967, -0.1764),
                arrivalPoint = queensGate, modeToHere = ModeChoice.Fixed(TravelMode.DRIVE),
                continuation = Continuation.Manual, stay = 60.minutes, leaveVehicle = true),
            Stop("2", place("Royal Albert Hall", 51.5009, -0.1774),
                modeToHere = ModeChoice.Fixed(TravelMode.WALK), stay = 15.minutes),
            Stop("3", place("British Museum", 51.5194, -0.1270),
                modeToHere = ModeChoice.Fixed(TravelMode.TRANSIT), stay = 45.minutes),
            Stop("4", place("Tower of London", 51.5081, -0.0759),
                modeToHere = ModeChoice.Fixed(TravelMode.BIKE_SHARE),
                continuation = Continuation.Manual, stay = 30.minutes),
            Stop("5", place("Borough Market", 51.5055, -0.0910),
                modeToHere = ModeChoice.Fastest(FastestOptions(bikeShare = true)), stay = 20.minutes),
            Stop("6", Place(null, queensGate, "Back to the car"),
                modeToHere = ModeChoice.Fastest(FastestOptions())),
            Stop("7", place("Home (Clapham Common)", 51.4618, -0.1384),
                modeToHere = ModeChoice.Fixed(TravelMode.DRIVE)),
        ),
        defaultContinuation = Continuation.Auto,
    )

    @Test
    fun saturdayInLondon() = runBlocking {
        assumeTrue(System.getProperty("waypoint.live") == "true")
        val tfl = TflClient()
        var calibration = DriveTimeCalibration()
        val planner = TripPlanner(
            routes = ValhallaRouteProvider(ValhallaRouteProvider.Endpoint.FOSSGIS),
            transit = CompositeTransitProvider(TflTransitProvider(tfl), TransitousTransitProvider()),
            bikes = TflBikePointProvider(tfl),
            calibration = { calibration },
            settings = { PlannerSettings() },
        )
        val routesSeen = mutableListOf<NavRoute>()
        fun collectRoutes(plan: Plan) = plan.legs.flatMap { it.segments }.forEach { s ->
            when (s) {
                is Segment.Drive -> routesSeen += s.route
                is Segment.Cycle -> routesSeen += s.route
                is Segment.Walk -> s.route?.let { routesSeen += it }
                else -> Unit
            }
        }

        // ---- Plan -------------------------------------------------------------------------------
        val t0 = (trip.time as TimeConstraint.DepartAt).at
        var plan = planner.plan(trip, victoria, t0 - 600.minutes)
        collectRoutes(plan)
        log.appendLine("PLAN: ${trip.name}, ${trip.stops.size} stops, from Victoria")
        printTimeline(plan)
        File(out, "plan.json").writeText(Json { prettyPrint = false }.encodeToString(Plan.serializer(), plan))

        assertEquals(7, plan.legs.size)
        assertEquals(listOf(TravelMode.DRIVE, TravelMode.WALK, TravelMode.TRANSIT, TravelMode.BIKE_SHARE), plan.legs.take(4).map { it.mode })
        assertTrue(plan.legs[0].segments.any { it is Segment.Park }, "drive ends with parking time")
        assertTrue(plan.legs[2].segments.any { it is Segment.TransitRide }, "transit leg has rides")
        assertEquals(5, plan.legs[3].segments.size, "bike share: walk, pickup, ride, dropoff, walk")
        assertEquals(TravelMode.DRIVE, plan.legs[6].mode)
        assertTrue(plan.legs.none { Warning.VEHICLE_LEFT_BEHIND in it.warnings }, "the trip comes back to the car")

        // ---- Run --------------------------------------------------------------------------------
        log.appendLine()
        log.appendLine("RUN (simulated clock)")
        val rt = TripRuntime(plan)
        var t = t0
        var drivesLearned = 0

        suspend fun apply(effects: List<RuntimeEffect>) {
            for (e in effects) when (e) {
                is RuntimeEffect.StartLeg -> {
                    val leg = rt.plan.legs[e.legIndex]
                    say(t, "Start leg ${e.legIndex + 1}: ${describe(leg)} to ${stopName(rt.plan, e.legIndex)}")
                }
                is RuntimeEffect.ShowArrival -> {
                    val wait = (rt.phase as TripPhase.AtStop).wait
                    say(t, "Arrived at ${stopName(rt.plan, e.stopIndex)} · " +
                        if (wait is StopWait.Countdown) "Auto: continuing in ${mins(wait.until - t)}" else "Manual: waiting for Start next leg")
                }
                is RuntimeEffect.Retime -> {
                    val before = rt.plan
                    val after = planner.retime(before, e.fromLeg, e.departAt, t)
                    rt.replacePlan(after)
                    collectRoutes(after)
                    val changed = after.legs.indices.drop(e.fromLeg).filter { before.legs[it].mode in setOf(TravelMode.TRANSIT, TravelMode.BIKE_SHARE) }
                    say(t, "Re-planned from leg ${e.fromLeg + 1} (leaving ${clock(e.departAt)}): " +
                        "ETA ${clock(before.arriveAt)} → ${clock(after.arriveAt)}" +
                        if (changed.isNotEmpty()) "; fresh transit/bike-share answers for leg ${changed.joinToString { "${it + 1}" }}" else "")
                }
                is RuntimeEffect.SkipStop -> {
                    val skipped = rt.plan.trip.stops.first { it.id == e.skippedStopId }.place.label
                    val here = rt.plan.trip.stops[e.atStopIndex].place.latLng
                    // Your car is still parked near the museum (the app works this out in TripSession.vehicleStateAt).
                    val vehicles = app.waypoint.trip.VehicleState(carAt = queensGate, carWithYou = false, bikeAt = null)
                    val after = planner.replanFrom(rt.plan, e.atStopIndex, here, t, e.skippedStopId, vehicles)
                    rt.replacePlan(after)
                    collectRoutes(after)
                    say(t, "Skipped $skipped. Re-planned: ${after.legs.size} legs, ETA ${clock(after.arriveAt)}")
                }
                is RuntimeEffect.DriveCompleted -> {
                    val old = calibration.factor(e.area, e.bucket)
                    calibration = calibration.record(e.area, e.bucket, e.planned, e.actual)
                    drivesLearned++
                    say(t, "Drive done in ${mins(e.actual)} (router said ${mins(e.planned)}). " +
                        "Drive-time factor ${e.area}/${e.bucket}: ×%.2f → ×%.2f".format(old, calibration.factor(e.area, e.bucket)))
                }
                RuntimeEffect.Finished -> say(t, "Trip complete.")
            }
        }

        /** Travel the current leg; [extra] is how much longer than planned it takes. */
        suspend fun travel(extra: Duration = Duration.ZERO, lateCheckAfter: Duration? = null) {
            val i = rt.currentLegIndex!!
            val leg = rt.plan.legs[i]
            val parking = leg.segments.filterIsInstance<Segment.Park>().fold(Duration.ZERO) { a, p -> a + p.duration }
            val travelTime = leg.duration - parking + extra
            if (lateCheckAfter != null) {
                // Live progress part-way: still `remaining` to go.
                val now = t + lateCheckAfter
                val remaining = travelTime - lateCheckAfter
                t = now
                apply(rt.onProgress(t, remaining))
                t = now + remaining
            } else {
                t += travelTime
            }
            apply(rt.onArrived(t))
        }

        apply(rt.start(t))
        // Leg 1, drive: Saturday-morning traffic makes it 35% slower than Valhalla thinks.
        val rawDrive = (plan.legs[0].segments[0] as Segment.Drive).rawDuration
        travel(extra = rawDrive * 1.35 - (plan.legs[0].segments[0] as Segment.Drive).adjustedDuration)
        // Manual stop: you stay 75 minutes instead of 60, then tap Start next leg.
        t += 75.minutes
        apply(rt.startNextLeg(t))
        // Leg 2, walk; then an Auto stop of 15 minutes counts down by itself.
        travel()
        repeat(16 * 60 / 30) { if (rt.phase is TripPhase.AtStop) { t += 30.seconds; apply(rt.onTick(t)) } }
        // Leg 3, transit: 10 minutes in, you're 8 minutes behind.
        travel(extra = 8.minutes, lateCheckAfter = 10.minutes)
        repeat(46 * 60 / 30) { if (rt.phase is TripPhase.AtStop) { t += 30.seconds; apply(rt.onTick(t)) } }
        // Leg 4, bike share; Manual stop at the Tower. You decide to skip Borough Market.
        travel()
        t += 30.minutes
        apply(rt.skipNextStop())
        apply(rt.startNextLeg(t))
        // Leg 5 (was 6): back to the car, Auto with no stay → straight into the drive home.
        travel()
        // Leg 6 (was 7): drive home, 20% slower than the router says.
        val home = rt.plan.legs[rt.currentLegIndex!!]
        val homeRaw = (home.segments[0] as Segment.Drive).rawDuration
        travel(extra = homeRaw * 1.2 - (home.segments[0] as Segment.Drive).adjustedDuration)

        assertIs<TripPhase.Finished>(rt.phase)
        assertEquals(6, rt.plan.legs.size)
        assertTrue(rt.plan.trip.stops.none { it.place.label == "Borough Market" })
        assertEquals(2, drivesLearned)

        log.appendLine()
        log.appendLine("FINAL TIMELINE")
        printTimeline(rt.plan)

        // Routes for the Ferrostar check.
        val dir = File(out, "routes").apply { deleteRecursively(); mkdirs() }
        routesSeen.distinctBy { it.osrmRouteJson }.forEachIndexed { k, r ->
            File(dir, "leg$k.route.json").writeText(r.osrmRouteJson)
            File(dir, "leg$k.waypoints.json").writeText(r.osrmWaypointsJson)
            File(dir, "leg$k.costing.txt").writeText(r.costing)
        }
        log.appendLine()
        log.appendLine("${routesSeen.distinctBy { it.osrmRouteJson }.size} guided routes written for the Ferrostar check.")
        File(out, "report.txt").writeText(log.toString())
    }

    private fun stopName(plan: Plan, i: Int) = plan.trip.stops[i].place.label

    private fun describe(leg: Leg): String = buildString {
        if (leg.chosenByFastest) append("Fastest → ")
        append(leg.mode.name.lowercase().replace('_', ' '))
        append(", ${mins(leg.duration)}")
        leg.segments.filterIsInstance<Segment.TransitRide>().takeIf { it.isNotEmpty() }?.let { rides ->
            append(" (").append(rides.joinToString(" + ") { "${it.line} ${it.board.name} → ${it.alight.name}" }).append(")")
        }
        leg.segments.filterIsInstance<Segment.BikePickup>().firstOrNull()?.let { append(" (bikes at ${it.dock.name}: ${it.dock.bikesAvailable})") }
    }

    private fun printTimeline(plan: Plan) {
        log.appendLine("  ${clock(plan.departAt)}  leave")
        plan.legs.forEachIndexed { i, leg ->
            val segs = leg.segments.joinToString(", ") { s ->
                when (s) {
                    is Segment.Drive -> "drive ${mins(s.adjustedDuration)} (router ${mins(s.rawDuration)})"
                    is Segment.Park -> "park ${mins(s.duration)}"
                    is Segment.Walk -> "walk ${mins(s.duration)}"
                    is Segment.Cycle -> "cycle ${mins(s.duration)}"
                    is Segment.TransitRide -> "${s.vehicle.name.lowercase()} ${s.line} ${clock(s.departAt)}–${clock(s.arriveAt)}"
                    is Segment.BikePickup -> "pick up at ${s.dock.name} (${s.dock.bikesAvailable} bikes)"
                    is Segment.BikeDropoff -> "dock at ${s.dock.name} (${s.dock.spacesAvailable} spaces)"
                }
            }
            val warn = leg.warnings.filter { it != Warning.NO_LIVE_TRAFFIC }
            log.appendLine("         ${describe(leg).substringBefore(" (")}: $segs" +
                (leg.runnerUp?.let { " · runner-up ${it.mode.name.lowercase()} ${mins(it.duration)}" } ?: "") +
                (if (warn.isNotEmpty()) " · $warn" else ""))
            log.appendLine("  ${clock(leg.arriveAt)}  ${i + 1}. ${plan.trip.stops[i].place.label}" +
                if (i < plan.legs.lastIndex) " (${if (plan.trip.continuationAt(plan.trip.stops[i]) == Continuation.Manual) "Manual" else "Auto"}, stay ${mins(plan.trip.stayAt(plan.trip.stops[i]))})" else "")
        }
    }
}
