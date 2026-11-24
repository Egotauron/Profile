package app.waypoint

import app.waypoint.session.RerouteGate
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class RerouteGateTest {
    @Test
    fun reroutesOnceOffTheRouteForASecondWhileMoving() {
        val g = RerouteGate()
        assertFalse(g.shouldReroute(0, 12.0), "first off-route fix")
        assertFalse(g.shouldReroute(500, 12.0))
        assertTrue(g.shouldReroute(1_000, 12.0), "still off a second later")
    }

    @Test
    fun neverWhileStandingStill() {
        val g = RerouteGate()
        for (t in 0L..10_000L step 500) assertFalse(g.shouldReroute(t, 0.3))
    }

    @Test
    fun aMomentOffTheRouteDoesNotCount() {
        val g = RerouteGate()
        assertFalse(g.shouldReroute(0, 12.0))
        // Back on the route for a while (Ferrostar stops asking), then off again: starts over.
        assertFalse(g.shouldReroute(5_000, 12.0))
        assertFalse(g.shouldReroute(5_500, 12.0))
        assertTrue(g.shouldReroute(6_000, 12.0))
    }

    @Test
    fun unknownSpeedCountsAsMoving() {
        val g = RerouteGate()
        g.shouldReroute(0, null)
        assertTrue(g.shouldReroute(1_000, null))
    }
}
