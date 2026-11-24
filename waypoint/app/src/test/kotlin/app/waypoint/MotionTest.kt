package app.waypoint

import android.location.Location
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.waypoint.session.Motion
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Telling standing still from moving with real Android Location objects, as phones report them. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MotionTest {
    private fun fix(metresNorth: Double, atMs: Long, speed: Float? = null, accuracy: Float = 12f) = Location("fused").apply {
        latitude = 51.5 + metresNorth / 111_320.0
        longitude = -0.12
        time = atMs
        this.accuracy = accuracy
        if (speed != null) this.speed = speed
    }

    @Test
    fun theGpsSpeedDecidesWhenThereIsOne() {
        assertTrue(Motion.isStill(listOf(fix(0.0, 0, speed = 0.2f))))
        assertFalse(Motion.isStill(listOf(fix(0.0, 0, speed = 1.3f))), "walking")
    }

    @Test
    fun withoutSpeedDriftWithinAFewMetresIsStill() {
        // Indoors: fixes wander 6–9 m around the same spot, with no speed.
        val drift = listOf(0.0, 7.0, -6.0, 9.0, 2.0).mapIndexed { i, m -> fix(m, i * 500L) }
        assertTrue(Motion.isStill(drift))
    }

    @Test
    fun withoutSpeedSteadyProgressIsMoving() {
        val driving = (0..6).map { fix(it * 6.0, it * 500L) } // 12 m/s
        assertFalse(Motion.isStill(driving))
    }

    @Test
    fun tooFewFixesToTellIsMoving() {
        assertFalse(Motion.isStill(listOf(fix(0.0, 0), fix(1.0, 500))))
    }
}
