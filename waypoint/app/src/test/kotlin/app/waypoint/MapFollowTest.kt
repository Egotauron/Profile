package app.waypoint

import app.waypoint.session.MapFollow
import app.waypoint.session.MapMove
import java.time.Instant
import kotlin.test.assertEquals
import org.junit.Test
import uniffi.ferrostar.GeographicCoordinate
import uniffi.ferrostar.UserLocation

class MapFollowTest {
    private fun at(metresNorth: Double, accuracy: Double = 12.0) =
        UserLocation(GeographicCoordinate(51.5 + metresNorth / 111_320.0, -0.12), accuracy, null, Instant.EPOCH, null)

    @Test
    fun driftWithinTheGpsAccuracyNeverMovesTheMap() {
        val shown = at(0.0)
        // Stopped with no speed, or a noisy one: fixes wander up to the accuracy radius.
        for (m in listOf(3.0, -7.0, 11.0, -11.5)) {
            assertEquals(MapMove.HOLD, MapFollow.decide(speed = null, still = false, shown = shown, fix = at(m)))
            assertEquals(MapMove.HOLD, MapFollow.decide(speed = 1.4, still = false, shown = shown, fix = at(m)))
        }
    }

    @Test
    fun aRealMoveIsShown() {
        assertEquals(MapMove.JUMP, MapFollow.decide(speed = 1.4, still = false, shown = at(0.0), fix = at(20.0)))
        assertEquals(MapMove.JUMP, MapFollow.decide(speed = null, still = false, shown = null, fix = at(0.0)), "first fix")
    }

    @Test
    fun drivingFollowsEveryFix() {
        assertEquals(MapMove.FOLLOW, MapFollow.decide(speed = 9.0, still = false, shown = at(0.0), fix = at(4.5)))
        // A speed spike while stopped doesn't count.
        assertEquals(MapMove.HOLD, MapFollow.decide(speed = 3.0, still = true, shown = at(0.0), fix = at(4.0)))
    }

    @Test
    fun theDeadbandIsAtLeastEightMetres() {
        assertEquals(MapMove.HOLD, MapFollow.decide(speed = null, still = false, shown = at(0.0, accuracy = 3.0), fix = at(7.0, accuracy = 3.0)))
    }
}
