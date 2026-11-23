package app.waypoint.trip

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

data class PlannerSettings(
    /** Added after every drive (finding a space, walking from the car park). Editable per stop. */
    val defaultParkingTime: Duration = 5.minutes,
    /** Drive around the Congestion Charge zone (Settings → Fastest defaults). */
    val avoidChargeZone: Boolean = false,
    val routeOptions: RouteOptions = RouteOptions(),
    /** A vehicle within this distance of you counts as "here". */
    val vehicleSameSpotMeters: Double = 150.0,
    /** False if your car doesn't meet the ULEZ standards: London drives then carry a ULEZ warning. */
    val ulezCompliantCar: Boolean = true,
)

class PlanningException(message: String, cause: Throwable? = null) : Exception(message, cause)

private const val LATEST_DEPARTURE_TRIES = 4
private val HEADWAY_PROBE = 15.minutes

/** Where your own car and bike are when planning starts (re-planning mid-trip passes this along). */
data class VehicleState(
    val carAt: LatLng?,
    val carWithYou: Boolean = false,
    val bikeAt: LatLng?,
    val bikeWithYou: Boolean = false,
)

/**
 * Builds one timeline through the stops in your order (spec §4.1).
 *
 * Consecutive fixed drive / walk / cycle legs go to the router as one request with the stops as
 * ordered `break` locations; transit legs are planned one at a time and chained.
 * Every leg departs at the previous arrival plus that stop's stay.
 */
class TripPlanner(
    private val routes: RouteProvider,
    private val transit: TransitProvider?,
    private val calibration: () -> DriveTimeCalibration = { DriveTimeCalibration() },
    private val settings: () -> PlannerSettings = { PlannerSettings() },
) {
    /**
     * Plans the trip. For Arrive by it plans forward from now (§4.1), then works out the latest
     * departure that still makes it by planning again from a few candidate departure times, since
     * transit timetables make the answer more than "arrival minus slack".
     */
    suspend fun plan(
        trip: Trip,
        start: LatLng,
        now: Instant,
        vehicles: VehicleState? = null,
    ): Plan {
        val forward = planForward(trip, start, now, vehicles)
        val target = (trip.time as? TimeConstraint.ArriveBy)?.at ?: return forward
        val guess = forward.latestDeparture ?: return forward
        if (guess <= now) return forward // already late, or it's time to go
        val timetabled = forward.legs.any { it.mode == TravelMode.TRANSIT }
        return forward.copy(latestDeparture = latestDeparture(trip, start, now, vehicles, target, guess, timetabled))
    }

    /**
     * The latest departure that still arrives by [target]. Without timetabled legs the forward
     * estimate is checked once. With transit, departure times are probed around it
     * (step one headway later, then halve the gap), keeping the latest one that makes it.
     */
    private suspend fun latestDeparture(
        trip: Trip,
        start: LatLng,
        now: Instant,
        vehicles: VehicleState?,
        target: Instant,
        firstGuess: Instant,
        timetabled: Boolean,
    ): Instant {
        suspend fun arrivalIfLeaving(at: Instant): Instant? = runCatching {
            planForward(trip.copy(time = TimeConstraint.DepartAt(at)), start, now, vehicles).arriveAt
        }.getOrNull()

        var ok: Instant? = null // latest departure known to make it
        var late: Instant? = null // earliest departure known to miss it
        var probe = firstGuess
        repeat(if (timetabled) LATEST_DEPARTURE_TRIES else 2) {
            val arrive = arrivalIfLeaving(probe) ?: return ok ?: firstGuess
            if (arrive <= target) ok = maxOf(ok ?: probe, probe) else late = minOf(late ?: probe, probe)
            val o = ok
            val l = late
            probe = when {
                o != null && l != null -> if (l - o <= 2.minutes) return o else o + (l - o) / 2
                o != null -> if (!timetabled) return o else o + HEADWAY_PROBE
                else -> probe - (arrive - target) - 1.minutes
            }
            if (probe < now) return ok ?: now
        }
        return ok ?: now
    }

    private suspend fun planForward(
        trip: Trip,
        start: LatLng,
        now: Instant,
        vehicles: VehicleState?,
    ): Plan = coroutineScope {
        if (trip.stops.isEmpty()) throw PlanningException("Add at least one stop")
        val s = settings()
        val t0 = when (val t = trip.time) {
            TimeConstraint.LeaveNow -> now
            is TimeConstraint.DepartAt -> t.at
            is TimeConstraint.ArriveBy -> now
        }
        val ctx = Ctx(trip, s, calibration())
        val prefetched = prefetchStreetRuns(ctx, start)

        val car = Vehicle(vehicles?.carAt ?: start, vehicles?.carWithYou ?: false)
        val bike = Vehicle(vehicles?.bikeAt ?: start, vehicles?.bikeWithYou ?: false)
        val legs = mutableListOf<Leg>()
        var clock = t0
        var position = start

        for ((i, stop) in trip.stops.withIndex()) {
            val fromId = if (i == 0) null else trip.stops[i - 1].id
            val choice = trip.modeTo(stop)
            var leg = when (choice) {
                is ModeChoice.Fixed -> fixedLeg(ctx, i, choice, position, clock, prefetched[i])
                    .toLeg(fromId, stop.id, chosenByFastest = false)
                is ModeChoice.Fastest -> fastestLeg(ctx, i, choice.options, position, clock, car, bike, fromId)
            }
            // Driving or cycling without your vehicle here.
            val vehicleHere = when (leg.mode) {
                TravelMode.DRIVE -> car.isNear(position, s)
                TravelMode.CYCLE -> bike.isNear(position, s)
                else -> true
            }
            if (!vehicleHere) leg = leg.copy(warnings = leg.warnings + Warning.VEHICLE_LEFT_BEHIND)

            val end = endpoint(stop, leg.mode)
            car.afterLeg(leg.mode == TravelMode.DRIVE, i, end, stop)
            bike.afterLeg(leg.mode == TravelMode.CYCLE, i, end, stop)
            legs += leg
            position = end
            clock = leg.arriveAt + if (i < trip.stops.lastIndex) trip.stayAt(stop) else Duration.ZERO
        }

        // Vehicle rule (§4.3): a car or bike left at a stop the trip doesn't come back to.
        for (v in listOf(car, bike)) {
            val lastUse = v.lastUsedLeg ?: continue
            val parted = lastUse + 1
            if (parted <= legs.lastIndex && !v.isNear(position, s)) {
                legs[parted] = legs[parted].let { it.copy(warnings = it.warnings + Warning.VEHICLE_LEFT_BEHIND) }
            }
        }
        finish(trip, start, legs, t0, now)
    }

    /**
     * Re-plans the time-sensitive legs from [fromLeg] onwards (transit), shifting the
     * others, when a leg starts earlier or later than planned (spec §4.1 "Re-planning").
     */
    suspend fun retime(plan: Plan, fromLeg: Int, departAt: Instant, now: Instant): Plan {
        val s = settings()
        val ctx = Ctx(plan.trip, s, calibration())
        val legs = plan.legs.toMutableList()
        var clock = departAt
        for (k in fromLeg..legs.lastIndex) {
            val old = legs[k]
            val stop = plan.trip.stops[k]
            val from = if (k == 0) plan.start else endpoint(plan.trip.stops[k - 1], legs[k - 1].mode)
            val to = endpoint(stop, old.mode)
            val replanned: LegOption? = when (old.mode) {
                TravelMode.TRANSIT -> runCatching {
                    transitOption(from, to, clock, allowedTransit(plan.trip.modeTo(stop)), forFastest = false)
                }.getOrNull()
                else -> null
            }
            legs[k] = if (replanned != null) {
                replanned.toLeg(old.fromStopId, old.toStopId, old.chosenByFastest).copy(runnerUp = old.runnerUp)
            } else {
                old.shiftedTo(clock)
            }
            clock = legs[k].arriveAt + if (k < legs.lastIndex) plan.trip.stayAt(stop) else Duration.ZERO
        }
        return finish(plan.trip, plan.start, legs, plan.departAt, now)
    }

    /**
     * Plans again from the stop you're at ([atStopIndex]), optionally without [dropStopId]
     * ("Skip next stop"). Legs already travelled are kept.
     */
    suspend fun replanFrom(
        plan: Plan,
        atStopIndex: Int,
        position: LatLng,
        now: Instant,
        dropStopId: String? = null,
        vehicles: VehicleState? = null,
    ): Plan {
        val before = plan.trip.stops.take(atStopIndex + 1)
        val after = plan.trip.stops.drop(atStopIndex + 1).filter { it.id != dropStopId }
        val newTrip = plan.trip.copy(stops = before + after)
        val kept = plan.legs.take(atStopIndex + 1)
        if (after.isEmpty()) return finish(newTrip, plan.start, kept, plan.departAt, now)
        val subTrip = plan.trip.copy(
            origin = Origin.CurrentLocation,
            stops = after,
            time = plan.trip.time as? TimeConstraint.ArriveBy ?: TimeConstraint.LeaveNow,
        )
        val sub = planForward(subTrip, position, now, vehicles)
        val joined = kept + sub.legs.mapIndexed { k, leg -> if (k == 0) leg.copy(fromStopId = before.last().id) else leg }
        return finish(newTrip, plan.start, joined, plan.departAt, now)
    }

    // ---------------------------------------------------------------------------------------------

    private class Ctx(val trip: Trip, val settings: PlannerSettings, val calibration: DriveTimeCalibration) {
        fun avoidChargeZone(choice: ModeChoice?): Boolean =
            settings.avoidChargeZone ||
                (trip.defaultMode as? ModeChoice.Fastest)?.options?.avoidChargeZone == true ||
                (choice as? ModeChoice.Fastest)?.options?.avoidChargeZone == true

        fun options(mode: TravelMode, choice: ModeChoice?): RouteOptions = settings.routeOptions.copy(
            excludePolygons = if (mode == TravelMode.DRIVE && avoidChargeZone(choice)) {
                listOf(London.congestionChargeZone)
            } else {
                emptyList()
            },
        )
    }

    private class Prefetched(val start: LatLng, val result: Deferred<RouteResult>, val index: Int)

    private class Vehicle(var at: LatLng, var withYou: Boolean) {
        var lastUsedLeg: Int? = null

        /** Stop index where you chose "Park here" / "Lock bike here"; not offered for the leg leaving it. */
        var parkedAtStop: Int? = null

        fun isNear(p: LatLng, s: PlannerSettings) = Geo.distanceMeters(at, p) <= s.vehicleSameSpotMeters

        /** Available for the leg that leaves stop [fromStop] (-1 = the start). */
        fun availableFor(fromStop: Int, p: LatLng, s: PlannerSettings) = isNear(p, s) && parkedAtStop != fromStop

        fun afterLeg(used: Boolean, legIndex: Int, end: LatLng, stop: Stop) {
            if (used) {
                at = end
                withYou = !stop.leaveVehicle
                lastUsedLeg = legIndex
                parkedAtStop = if (stop.leaveVehicle) legIndex else null
            } else {
                withYou = false
            }
        }
    }

    /** Where you are after arriving at [stop] by [mode]: a drive ends at the car park if one is set. */
    private fun endpoint(stop: Stop, mode: TravelMode): LatLng =
        if (mode == TravelMode.DRIVE) stop.arrivalPoint ?: stop.place.latLng else stop.place.latLng

    private val streetModes = setOf(TravelMode.DRIVE, TravelMode.WALK, TravelMode.CYCLE)

    /** Starts one router request per run of consecutive fixed legs with the same street mode. */
    private fun kotlinx.coroutines.CoroutineScope.prefetchStreetRuns(ctx: Ctx, start: LatLng): Map<Int, Prefetched> {
        val stops = ctx.trip.stops
        val fixedStreet: List<TravelMode?> = stops.map { stop ->
            (ctx.trip.modeTo(stop) as? ModeChoice.Fixed)?.mode?.takeIf { it in streetModes }
        }
        val out = mutableMapOf<Int, Prefetched>()
        var i = 0
        while (i < stops.size) {
            val mode = fixedStreet[i]
            if (mode == null) { i++; continue }
            var j = i
            while (j + 1 < stops.size && fixedStreet[j + 1] == mode) j++
            val runStart = if (i == 0) start else {
                val prev = stops[i - 1]
                if (fixedStreet[i - 1] == TravelMode.DRIVE) endpoint(prev, TravelMode.DRIVE) else prev.place.latLng
            }
            val points = listOf(runStart) + (i..j).map { endpoint(stops[it], mode) }
            val request = RouteRequest(mode, points, ctx.options(mode, ctx.trip.modeTo(stops[i])))
            val deferred = async { routes.route(request) }
            for (k in i..j) out[k] = Prefetched(if (k == i) runStart else points[k - i], deferred, k - i)
            i = j + 1
        }
        return out
    }

    private suspend fun fixedLeg(
        ctx: Ctx,
        i: Int,
        choice: ModeChoice.Fixed,
        position: LatLng,
        departAt: Instant,
        prefetched: Prefetched?,
    ): LegOption {
        val stop = ctx.trip.stops[i]
        val target = endpoint(stop, choice.mode)
        return when (choice.mode) {
            TravelMode.DRIVE, TravelMode.WALK, TravelMode.CYCLE -> {
                val (route, ignored) = if (prefetched != null && Geo.distanceMeters(prefetched.start, position) < 30.0) {
                    val result = awaitRoute(prefetched.result, stop)
                    result.legs[prefetched.index] to result.exclusionsIgnored
                } else {
                    val result = requestRoute(RouteRequest(choice.mode, listOf(position, target), ctx.options(choice.mode, choice)), stop)
                    result.legs.single() to result.exclusionsIgnored
                }
                streetOption(ctx, choice.mode, route, departAt, stop, ignored, ctx.avoidChargeZone(choice))
            }
            TravelMode.TRANSIT -> {
                transitOption(position, target, departAt, choice.transit, forFastest = false)
                    ?: walkFallback(ctx, position, target, departAt, stop, Warning.NO_TRANSIT_MATCH)
            }
        }
    }

    /** Fastest (§4.3): every enabled candidate in parallel, earliest arrival wins, runner-up kept. */
    private suspend fun fastestLeg(
        ctx: Ctx,
        i: Int,
        options: FastestOptions,
        position: LatLng,
        departAt: Instant,
        car: Vehicle,
        bike: Vehicle,
        fromId: String?,
    ): Leg = coroutineScope {
        val s = ctx.settings
        val stop = ctx.trip.stops[i]
        val choice = ctx.trip.modeTo(stop)
        val avoid = ctx.avoidChargeZone(choice)

        // Vehicle rule: having arrived by your own car or bike (and not parked it here), you stay on it.
        val forcedMode = when {
            car.withYou && options.drive -> TravelMode.DRIVE
            bike.withYou && options.cycle -> TravelMode.CYCLE
            else -> null
        }
        val candidates = buildList {
            if (forcedMode != null) {
                add(forcedMode)
            } else {
                if (options.walk) add(TravelMode.WALK)
                if (options.transit.isNotEmpty() && transit != null) add(TravelMode.TRANSIT)
                if (options.cycle && bike.availableFor(i - 1, position, s)) add(TravelMode.CYCLE)
                if (options.drive && car.availableFor(i - 1, position, s)) add(TravelMode.DRIVE)
            }
        }
        if (candidates.isEmpty()) throw PlanningException("Fastest has no modes switched on for ${stop.place.label}")

        val results: List<Pair<TravelMode, Result<LegOption?>>> = candidates.map { mode ->
            async {
                mode to runCatching {
                    when (mode) {
                        TravelMode.TRANSIT -> transitOption(position, stop.place.latLng, departAt, options.transit, forFastest = true)
                        else -> {
                            val target = endpoint(stop, mode)
                            val r = routes.route(RouteRequest(mode, listOf(position, target), ctx.options(mode, choice)))
                            streetOption(ctx, mode, r.legs.single(), departAt, stop, r.exclusionsIgnored, avoid)
                        }
                    }
                }
            }
        }.awaitAll()

        val options2 = results.mapNotNull { (_, r) -> r.getOrNull() }.sortedBy { it.arriveAt }
        val best = options2.firstOrNull()
            ?: throw PlanningException(
                "No way found to ${stop.place.label}",
                results.firstNotNullOfOrNull { it.second.exceptionOrNull() },
            )
        val extraWarnings = buildList {
            if (TravelMode.TRANSIT in candidates && results.none { it.first == TravelMode.TRANSIT && it.second.getOrNull() != null }) {
                add(Warning.NO_TRANSIT_MATCH)
            }
        }
        best.toLeg(fromId, stop.id, chosenByFastest = true).copy(
            runnerUp = options2.getOrNull(1),
            warnings = best.warnings + extraWarnings,
        )
    }

    private suspend fun awaitRoute(d: Deferred<RouteResult>, stop: Stop): RouteResult = try {
        d.await()
    } catch (e: ProviderException) {
        throw PlanningException("Couldn't route to ${stop.place.label}: ${e.message}", e)
    }

    private suspend fun requestRoute(request: RouteRequest, stop: Stop): RouteResult = try {
        routes.route(request)
    } catch (e: ProviderException) {
        throw PlanningException("Couldn't route to ${stop.place.label}: ${e.message}", e)
    }

    private fun streetOption(
        ctx: Ctx,
        mode: TravelMode,
        route: NavRoute,
        departAt: Instant,
        stop: Stop,
        exclusionsIgnored: Boolean,
        avoidChargeZone: Boolean,
    ): LegOption {
        val raw = route.durationSeconds.secondsDuration().roundedToSeconds()
        return when (mode) {
            TravelMode.WALK -> LegOption(
                mode, departAt, departAt + raw,
                listOf(Segment.Walk(route, raw, route.geometry.first(), route.geometry.last())),
            )
            TravelMode.CYCLE -> LegOption(mode, departAt, departAt + raw, listOf(Segment.Cycle(route, raw)))
            TravelMode.DRIVE -> {
                val area = DriveTimeCalibration.areaOf(route.geometry)
                val bucket = DriveTimeCalibration.bucketOf(departAt)
                val adjusted = ctx.calibration.adjust(raw, area, bucket).roundedToSeconds()
                val parking = stop.parkingTime ?: ctx.settings.defaultParkingTime
                val charged = London.driveIsCharged(route.geometry, departAt, departAt + adjusted)
                val warnings = buildList {
                    add(Warning.NO_LIVE_TRAFFIC)
                    if (charged) add(Warning.CHARGE_ZONE)
                    if (charged && avoidChargeZone && exclusionsIgnored) add(Warning.CHARGE_ZONE_NOT_AVOIDED)
                    if (!ctx.settings.ulezCompliantCar && London.driveIsInUlez(route.geometry, departAt)) add(Warning.ULEZ)
                }
                val segments = buildList {
                    add(Segment.Drive(route, raw, adjusted))
                    if (parking > Duration.ZERO) add(Segment.Park(parking))
                }
                LegOption(mode, departAt, departAt + adjusted + parking, segments, warnings)
            }
            else -> error("not a street mode: $mode")
        }
    }

    private fun allowedTransit(choice: ModeChoice): Set<TransitVehicle> = when (choice) {
        is ModeChoice.Fixed -> choice.transit
        is ModeChoice.Fastest -> choice.options.transit
    }

    /**
     * One transit request with alternatives, filtered to the allowed vehicles. Walk-only answers
     * don't count as transit for Fastest (the walk candidate covers them).
     */
    private suspend fun transitOption(
        from: LatLng,
        to: LatLng,
        departAt: Instant,
        allowed: Set<TransitVehicle>,
        forFastest: Boolean,
    ): LegOption? {
        val provider = transit ?: return null
        val itineraries = try {
            provider.plan(from, to, departAt)
        } catch (e: ProviderException) {
            return null
        }
        val ok = itineraries.filter { allowed.containsAll(it.vehicles) && !(forFastest && it.vehicles.isEmpty()) }
        val best = ok.minByOrNull { it.arriveAt } ?: return null
        return LegOption(TravelMode.TRANSIT, minOf(departAt, best.departAt), best.arriveAt, best.segments)
    }

    /** When transit has nothing that fits, the leg is shown on foot with a warning. */
    private suspend fun walkFallback(ctx: Ctx, from: LatLng, to: LatLng, departAt: Instant, stop: Stop, warning: Warning): LegOption {
        val route = requestRoute(RouteRequest(TravelMode.WALK, listOf(from, to), ctx.options(TravelMode.WALK, null)), stop).legs.single()
        val option = streetOption(ctx, TravelMode.WALK, route, departAt, stop, false, false)
        return option.copy(warnings = option.warnings + warning)
    }

    private fun finish(trip: Trip, start: LatLng, legs: List<Leg>, t0: Instant, now: Instant): Plan {
        val arrive = legs.last().arriveAt
        val arriveBy = trip.time as? TimeConstraint.ArriveBy
        val slack = arriveBy?.let { it.at - arrive }
        return Plan(
            trip = trip,
            start = start,
            legs = legs,
            planWarnings = if (slack != null && slack.isNegative()) listOf(Warning.LATE_FOR_ARRIVE_BY) else emptyList(),
            arriveBySlack = slack,
            latestDeparture = slack?.let { t0 + it },
            plannedAt = now,
        )
    }
}

fun LegOption.toLeg(fromStopId: String?, toStopId: String, chosenByFastest: Boolean): Leg =
    Leg(fromStopId, toStopId, mode, departAt, arriveAt, segments, warnings, chosenByFastest = chosenByFastest)

/** Moves a leg (and its transit rides) to a new departure time without re-routing it. */
fun Leg.shiftedTo(newDepart: Instant): Leg {
    val delta = newDepart - departAt
    return copy(
        departAt = newDepart,
        arriveAt = arriveAt + delta,
        segments = segments.map { seg ->
            if (seg is Segment.TransitRide) seg.copy(departAt = seg.departAt + delta, arriveAt = seg.arriveAt + delta) else seg
        },
    )
}
