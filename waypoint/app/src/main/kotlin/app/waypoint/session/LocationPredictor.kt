package app.waypoint.session

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import uniffi.ferrostar.GeographicCoordinate
import uniffi.ferrostar.UserLocation

/**
 * Keeps the car moving on the map between GPS fixes: from the newest fix, along its course at its
 * speed, for at most [MAX_AHEAD_S] (after that it waits for the next fix rather than guess). Only
 * the maps use this; off-route checks and instructions keep using the real fixes.
 */
class LocationPredictor {
    private var fix: UserLocation? = null
    private var fixAtMs = 0L
    private var speed = 0.0

    /**
     * Call with the newest location; [nowMs] is a monotonic clock (elapsedRealtime). Only the speed
     * the GPS reports is used: one worked out from two fixes is mostly GPS drift when you're still.
     */
    fun update(location: UserLocation, nowMs: Long) {
        val previous = fix
        if (previous != null && previous.timestamp == location.timestamp && previous.coordinates == location.coordinates) return
        speed = location.speed?.value?.takeUnless { it < STILL_MPS } ?: 0.0
        fix = location
        fixAtMs = nowMs
    }

    /** Where the car is now, by the last fix's speed and course. */
    fun predict(nowMs: Long): UserLocation? {
        val f = fix ?: return null
        val course = f.courseOverGround?.degrees?.toDouble() ?: return f
        val seconds = ((nowMs - fixAtMs) / 1000.0).coerceIn(0.0, MAX_AHEAD_S)
        val meters = speed * seconds
        if (meters < 0.05) return f
        return f.copy(coordinates = moved(f.coordinates, course, meters))
    }

    companion object {
        const val MAX_AHEAD_S = 1.5
        private const val M_PER_DEG = 111_320.0

        /** Slower than this (m/s) isn't worth predicting: mostly GPS drift. */
        const val STILL_MPS = 1.0

        /** [meters] along [bearing] (degrees from north); flat-earth is plenty for a few metres. */
        fun moved(from: GeographicCoordinate, bearing: Double, meters: Double): GeographicCoordinate {
            val b = bearing * PI / 180
            val lat = from.lat + meters * cos(b) / M_PER_DEG
            val lng = from.lng + meters * sin(b) / (M_PER_DEG * cos(from.lat * PI / 180))
            return GeographicCoordinate(lat, lng)
        }
    }
}
