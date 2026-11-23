package app.waypoint.trip

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

sealed interface TripPhase {
    data object NotStarted : TripPhase
    data class OnLeg(val legIndex: Int, val startedAt: Instant) : TripPhase
    data class AtStop(val stopIndex: Int, val arrivedAt: Instant, val wait: StopWait) : TripPhase
    data class Finished(val at: Instant) : TripPhase
}

sealed interface StopWait {
    /** Auto: the next leg starts by itself at [until] ("Continuing to Stop 3 in 2:00"). */
    data class Countdown(val until: Instant) : StopWait
    /** Manual (or a paused countdown): waits for "Start next leg". */
    data object ForYou : StopWait
}

sealed interface RuntimeEffect {
    data class StartLeg(val legIndex: Int) : RuntimeEffect
    /** Show the arrival card for this stop. */
    data class ShowArrival(val stopIndex: Int) : RuntimeEffect
    /** Re-plan time-sensitive legs from [fromLeg], departing at [departAt] ([TripPlanner.retime]). */
    data class Retime(val fromLeg: Int, val departAt: Instant) : RuntimeEffect
    /** Skip the stop after [atStopIndex]: plan again from here without it ([TripPlanner.replanFrom]). */
    data class SkipStop(val atStopIndex: Int, val skippedStopId: String) : RuntimeEffect
    /** A drive leg finished: feed the drive-time factor (§4.3). */
    data class DriveCompleted(
        val legIndex: Int,
        val area: DriveArea,
        val bucket: TimeBucket,
        val planned: Duration,
        val actual: Duration,
    ) : RuntimeEffect
    data object Finished : RuntimeEffect
}

/**
 * The trip's runtime rules (spec §4.1–4.2), independent of Android: what happens on arrival, at
 * each stop (Auto / Manual), and when to re-plan. The `TripSession` service feeds it events and
 * carries out the effects it returns.
 */
class TripRuntime(plan: Plan) {
    var plan: Plan = plan
        private set
    var phase: TripPhase = TripPhase.NotStarted
        private set

    /** Delay (beyond the plan) already re-planned for on the current leg, to avoid repeat requests. */
    private var delayHandled: Duration = Duration.ZERO

    val currentLegIndex: Int?
        get() = when (val p = phase) {
            is TripPhase.OnLeg -> p.legIndex
            else -> null
        }

    /** "Stop 2 of 5": the stop being travelled to or waited at, 1-based. */
    val stopNumber: Int
        get() = when (val p = phase) {
            TripPhase.NotStarted -> 1
            is TripPhase.OnLeg -> p.legIndex + 1
            is TripPhase.AtStop -> p.stopIndex + 1
            is TripPhase.Finished -> plan.legs.size
        }

    fun start(now: Instant): List<RuntimeEffect> {
        check(phase == TripPhase.NotStarted) { "trip already started" }
        return beginLeg(0, now)
    }

    /** The current leg reached its stop. */
    fun onArrived(now: Instant): List<RuntimeEffect> {
        val on = phase as? TripPhase.OnLeg ?: return emptyList()
        val i = on.legIndex
        val leg = plan.legs[i]
        val effects = mutableListOf<RuntimeEffect>()
        leg.segments.filterIsInstance<Segment.Drive>().singleOrNull()?.let { drive ->
            effects += RuntimeEffect.DriveCompleted(
                legIndex = i,
                area = DriveTimeCalibration.areaOf(drive.route.geometry),
                bucket = DriveTimeCalibration.bucketOf(on.startedAt),
                planned = drive.rawDuration,
                actual = now - on.startedAt,
            )
        }
        if (i == plan.legs.lastIndex) {
            phase = TripPhase.Finished(now)
            return effects + RuntimeEffect.Finished
        }
        val stop = plan.trip.stops[i]
        val stay = plan.trip.stayAt(stop)
        when (plan.trip.continuationAt(stop)) {
            Continuation.Auto -> if (stay <= Duration.ZERO) {
                // Auto with no stay: carry on through the waypoint.
                phase = TripPhase.AtStop(i, now, StopWait.Countdown(now))
                return effects + startNextLeg(now)
            } else {
                phase = TripPhase.AtStop(i, now, StopWait.Countdown(now + stay))
            }
            Continuation.Manual -> phase = TripPhase.AtStop(i, now, StopWait.ForYou)
        }
        return effects + RuntimeEffect.ShowArrival(i)
    }

    /** Called about once a second: runs the Auto countdown. */
    fun onTick(now: Instant): List<RuntimeEffect> {
        val at = phase as? TripPhase.AtStop ?: return emptyList()
        val wait = at.wait as? StopWait.Countdown ?: return emptyList()
        return if (now >= wait.until) startNextLeg(now) else emptyList()
    }

    /**
     * Live progress on the current leg ([remaining] = time to its stop). Running more than 5 minutes
     * behind re-plans the time-sensitive legs after it.
     */
    fun onProgress(now: Instant, remaining: Duration): List<RuntimeEffect> {
        val on = phase as? TripPhase.OnLeg ?: return emptyList()
        val i = on.legIndex
        if (i == plan.legs.lastIndex) return emptyList()
        val leg = plan.legs[i]
        val parking = leg.segments.filterIsInstance<Segment.Park>().fold(Duration.ZERO) { acc, p -> acc + p.duration }
        val expectedArrival = now + remaining + parking
        val delay = expectedArrival - leg.arriveAt
        if (delay > BEHIND_THRESHOLD && delay - delayHandled > BEHIND_THRESHOLD) {
            delayHandled = delay
            val stay = plan.trip.stayAt(plan.trip.stops[i])
            return listOf(RuntimeEffect.Retime(i + 1, expectedArrival + stay))
        }
        return emptyList()
    }

    /** "Start next leg" (or the countdown ran out / "Start now"). */
    fun startNextLeg(now: Instant): List<RuntimeEffect> {
        val at = phase as? TripPhase.AtStop ?: return emptyList()
        return beginLeg(at.stopIndex + 1, now)
    }

    /** "Pause" on the countdown card: this stop becomes Manual. */
    fun pauseCountdown() {
        val at = phase as? TripPhase.AtStop ?: return
        if (at.wait is StopWait.Countdown) phase = at.copy(wait = StopWait.ForYou)
    }

    /** "Skip next stop" on the arrival card. */
    fun skipNextStop(): List<RuntimeEffect> {
        val at = phase as? TripPhase.AtStop ?: return emptyList()
        val next = plan.trip.stops.getOrNull(at.stopIndex + 1) ?: return emptyList()
        return listOf(RuntimeEffect.SkipStop(at.stopIndex, next.id))
    }

    /** Installs a re-planned or retimed plan. Legs up to the current position must be unchanged. */
    fun replacePlan(newPlan: Plan) {
        plan = newPlan
        delayHandled = Duration.ZERO
        if (phase is TripPhase.AtStop && (phase as TripPhase.AtStop).stopIndex >= newPlan.legs.lastIndex) {
            phase = TripPhase.Finished((phase as TripPhase.AtStop).arrivedAt)
        }
    }

    fun end(now: Instant) {
        phase = TripPhase.Finished(now)
    }

    private fun beginLeg(index: Int, now: Instant): List<RuntimeEffect> {
        phase = TripPhase.OnLeg(index, now)
        delayHandled = Duration.ZERO
        // Re-plan when leaving earlier or later than planned, and on every "Start next leg" while
        // time-sensitive legs (transit, Arrive by) remain (spec §4.1).
        val offSchedule = (now - plan.legs[index].departAt).absoluteValue > EARLY_LATE_THRESHOLD
        val timeSensitive = plan.trip.time is TimeConstraint.ArriveBy ||
            plan.legs.drop(index).any { it.mode == TravelMode.TRANSIT }
        return buildList {
            if (offSchedule || (index > 0 && timeSensitive)) add(RuntimeEffect.Retime(index, now))
            add(RuntimeEffect.StartLeg(index))
        }
    }

    companion object {
        val BEHIND_THRESHOLD: Duration = 5.minutes
        val EARLY_LATE_THRESHOLD: Duration = 2.minutes
    }
}
