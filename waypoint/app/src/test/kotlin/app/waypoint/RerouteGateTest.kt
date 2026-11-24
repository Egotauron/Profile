package app.waypoint

import app.waypoint.session.RerouteGate
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class RerouteGateTest {
    @Test
    fun reroutesOnceOffTheRouteForASecondWhileMoving() {
        val g = RerouteGate()
        assertFalse(g.shouldReroute(0, still = false), "first off-route fix")
        assertFalse(g.shouldReroute(500, still = false))
        assertTrue(g.shouldReroute(1_000, still = false), "still off a second later")
    }

    @Test
    fun neverWhileStandingStill() {
        val g = RerouteGate()
        for (t in 0L..10_000L step 500) assertFalse(g.shouldReroute(t, still = true))
    }

    @Test
    fun aMomentOffTheRouteDoesNotCount() {
        val g = RerouteGate()
        assertFalse(g.shouldReroute(0, still = false))
        // Back on the route for a while (Ferrostar stops asking), then off again: starts over.
        assertFalse(g.shouldReroute(5_000, still = false))
        assertFalse(g.shouldReroute(5_500, still = false))
        assertTrue(g.shouldReroute(6_000, still = false))
    }
}
