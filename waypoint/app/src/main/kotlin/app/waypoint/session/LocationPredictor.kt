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

/** What the map does with a new fix (see TripSession's map view model). */
enum class MapMove { FOLLOW, HOLD, JUMP }

object MapFollow {
    /** Faster than this (m/s) is clearly moving: the map follows every fix. */
    const val CLEARLY_MOVING_MPS = 2.0
    /** Otherwise the map moves only for fixes at least this far away (or the GPS accuracy). */
    const val DEADBAND_M = 8.0

    /**
     * Clearly moving: follow. Otherwise hold the map still unless the fix landed further from
     * what's shown than GPS drift explains (its accuracy, at least [DEADBAND_M]); then jump there.
     */
    fun decide(speed: Double?, still: Boolean, shown: UserLocation?, fix: UserLocation): MapMove {
        if ((speed ?: 0.0) >= CLEARLY_MOVING_MPS && !still) return MapMove.FOLLOW
        if (shown == null) return MapMove.JUMP
        return if (metres(shown, fix) <= deadband(fix)) MapMove.HOLD else MapMove.JUMP
    }

    fun deadband(fix: UserLocation): Double = maxOf(DEADBAND_M, fix.horizontalAccuracy.takeIf { it.isFinite() } ?: DEADBAND_M)

    fun metres(a: UserLocation, b: UserLocation): Double {
        val dy = (b.coordinates.lat - a.coordinates.lat) * 111_320.0
        val dx = (b.coordinates.lng - a.coordinates.lng) * 111_320.0 * cos(a.coordinates.lat * PI / 180)
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}
