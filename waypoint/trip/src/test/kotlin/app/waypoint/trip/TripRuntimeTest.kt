package app.waypoint.trip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

class TripRuntimeTest {
    private val home = LatLng(51.7520, -1.2577)
    private val a = place("A", 51.7524, -1.2566)
    private val b = place("B", 51.7600, -1.2500)
    private val c = place("C", 51.7600, -1.2100)
    private val t0 = Instant.parse("2026-11-29T15:00:00Z")

    private suspend fun planOf(trip: Trip): Plan =
        TripPlanner(FakeRoutes(), FakeTransit(), null).plan(trip, home, t0)

    private fun trip(vararg stops: Stop, mode: TravelMode = TravelMode.WALK) =
        Trip(id = "t", stops = stops.toList(), defaultMode = ModeChoice.Fixed(mode))

    @Test
    fun autoStopCountsDownThenStartsTheNextLeg() = runTest {
        val plan = planOf(trip(stop("1", a) { copy(stay = 2.minutes) }, stop("2", b)))
        val rt = TripRuntime(plan)
        assertEquals(listOf<RuntimeEffect>(RuntimeEffect.StartLeg(0)), rt.start(t0))
        val arrive = plan.legs[0].arriveAt
        assertEquals(listOf<RuntimeEffect>(RuntimeEffect.ShowArrival(0)), rt.onArrived(arrive))
        val at = assertIs<TripPhase.AtStop>(rt.phase)
        assertEquals(StopWait.Countdown(arrive + 2.minutes), at.wait)
        assertTrue(rt.onTick(arrive + 119.seconds).isEmpty())
        assertEquals(listOf<RuntimeEffect>(RuntimeEffect.StartLeg(1)), rt.onTick(arrive + 2.minutes))
        assertEquals(2, rt.stopNumber)
        assertEquals(listOf<RuntimeEffect>(RuntimeEffect.Finished), rt.onArrived(plan.legs[1].arriveAt))
        assertIs<TripPhase.Finished>(rt.phase)
    }

    @Test
    fun autoWithNoStayCarriesStraightOn() = runTest {
        val plan = planOf(trip(stop("1", a), stop("2", b)))
        val rt = TripRuntime(plan)
        rt.start(t0)
        assertEquals(listOf<RuntimeEffect>(RuntimeEffect.StartLeg(1)), rt.onArrived(plan.legs[0].arriveAt))
    }

    @Test
    fun manualWaitsAndPauseTurnsAutoIntoManual() = runTest {
        val plan = planOf(
            trip(
                stop("1", a) { copy(continuation = Continuation.Manual, stay = 15.minutes) },
                stop("2", b) { copy(stay = 5.minutes) },
                stop("3", c),
            ),
        )
        val rt = TripRuntime(plan)
        rt.start(t0)
        rt.onArrived(plan.legs[0].arriveAt)
        assertEquals(StopWait.ForYou, (rt.phase as TripPhase.AtStop).wait)
        assertTrue(rt.onTick(plan.legs[0].arriveAt + 60.minutes).isEmpty())

        // Leaving 25 minutes late re-plans what follows.
        val leave = plan.legs[1].departAt + 25.minutes
        assertEquals(
            listOf(RuntimeEffect.Retime(1, leave), RuntimeEffect.StartLeg(1)),
            rt.startNextLeg(leave),
        )

        rt.onArrived(leave + plan.legs[1].duration)
        rt.pauseCountdown()
        assertEquals(StopWait.ForYou, (rt.phase as TripPhase.AtStop).wait)
        assertTrue(rt.onTick(leave + 180.minutes).isEmpty())
    }

    @Test
    fun runningFiveMinutesBehindReplansOnce() = runTest {
        val plan = planOf(trip(stop("1", a) { copy(stay = 10.minutes) }, stop("2", b)))
        val rt = TripRuntime(plan)
        rt.start(t0)
        val leg = plan.legs[0]
        assertTrue(rt.onProgress(t0 + 1.minutes, leg.duration).isEmpty())
        val now = t0 + 6.minutes
        val effects = rt.onProgress(now, leg.duration)
        assertEquals(listOf<RuntimeEffect>(RuntimeEffect.Retime(1, now + leg.duration + 10.minutes)), effects)
        assertTrue(rt.onProgress(now + 1.minutes, leg.duration).isEmpty())
        assertEquals(1, rt.onProgress(now + 6.minutes, leg.duration).size)
    }

    @Test
    fun finishingADriveFeedsTheDriveTimeFactor() = runTest {
        val plan = planOf(trip(stop("1", c), mode = TravelMode.DRIVE))
        val rt = TripRuntime(plan)
        rt.start(t0)
        val effects = rt.onArrived(t0 + 9.minutes)
        val drive = effects.filterIsInstance<RuntimeEffect.DriveCompleted>().single()
        assertEquals(9.minutes, drive.actual)
        assertEquals((plan.legs[0].segments[0] as Segment.Drive).rawDuration, drive.planned)
        assertEquals(DriveArea.ELSEWHERE, drive.area)
        assertEquals(TimeBucket.OFF_PEAK, drive.bucket)
        assertTrue(RuntimeEffect.Finished in effects)
    }

    @Test
    fun skipNextStopAsksForAReplan() = runTest {
        val plan = planOf(trip(stop("1", a) { copy(continuation = Continuation.Manual) }, stop("2", b), stop("3", c)))
        val rt = TripRuntime(plan)
        rt.start(t0)
        rt.onArrived(plan.legs[0].arriveAt)
        assertEquals(listOf<RuntimeEffect>(RuntimeEffect.SkipStop(0, "2")), rt.skipNextStop())
    }
}
