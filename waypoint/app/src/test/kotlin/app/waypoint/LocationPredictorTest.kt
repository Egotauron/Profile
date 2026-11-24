package app.waypoint

import app.waypoint.session.LocationPredictor
import java.time.Instant
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import uniffi.ferrostar.CourseOverGround
import uniffi.ferrostar.GeographicCoordinate
import uniffi.ferrostar.Speed
import uniffi.ferrostar.UserLocation

class LocationPredictorTest {
    private fun fix(lat: Double, lng: Double, course: Int?, speed: Double?, atMs: Long) = UserLocation(
        GeographicCoordinate(lat, lng), 5.0, course?.let { CourseOverGround(it.toUShort(), 5u) },
        Instant.ofEpochMilli(atMs), speed?.let { Speed(it, 0.5) },
    )

    private fun meters(a: GeographicCoordinate, b: GeographicCoordinate): Double {
        val dy = (b.lat - a.lat) * 111_320.0
        val dx = (b.lng - a.lng) * 111_320.0 * cos(Math.toRadians(a.lat))
        return sqrt(dx * dx + dy * dy)
    }

    @Test
    fun movesOnAlongTheCourseBetweenFixes() {
        val p = LocationPredictor()
        val start = fix(51.5, -0.12, course = 90, speed = 10.0, atMs = 0)
        p.update(start, nowMs = 1_000)
        val half = p.predict(1_500)!!
        assertEquals(5.0, meters(start.coordinates, half.coordinates), 0.05)
        assertTrue(half.coordinates.lng > start.coordinates.lng, "heading east")
        assertEquals(start.coordinates.lat, half.coordinates.lat, 1e-9)
        // Never more than 1.5 s ahead of the last fix.
        assertEquals(15.0, meters(start.coordinates, p.predict(10_000)!!.coordinates), 0.05)
    }

    @Test
    fun aNewFixStartsAgainFromIt() {
        val p = LocationPredictor()
        p.update(fix(51.5, -0.12, 0, 10.0, 0), nowMs = 0)
        val next = fix(51.50009, -0.12, 0, 10.0, 1_000)
        p.update(next, nowMs = 1_000)
        assertEquals(next.coordinates, p.predict(1_000)!!.coordinates)
        // The same fix again (a tick, not a new fix) doesn't reset the clock.
        p.update(next, nowMs = 1_200)
        assertEquals(2.0, meters(next.coordinates, p.predict(1_200)!!.coordinates), 0.05)
    }

    @Test
    fun standingStillOrNoDirectionStaysPut() {
        val p = LocationPredictor()
        val still = fix(51.5, -0.12, 90, 0.3, 0)
        p.update(still, 0)
        assertEquals(still.coordinates, p.predict(1_000)!!.coordinates)
        val noCourse = fix(51.5, -0.12, null, 10.0, 0)
        p.update(noCourse, 2_000)
        assertEquals(noCourse.coordinates, p.predict(3_000)!!.coordinates)
    }

    @Test
    fun speedFromTheLastTwoFixesWhenTheFixHasNone() {
        val p = LocationPredictor()
        p.update(fix(51.5, -0.12, 0, null, 0), 0)
        val second = fix(51.5 + 8 / 111_320.0, -0.12, 0, null, 1_000) // 8 m north in 1 s
        p.update(second, 1_000)
        assertEquals(4.0, meters(second.coordinates, p.predict(1_500)!!.coordinates), 0.05)
    }
}
