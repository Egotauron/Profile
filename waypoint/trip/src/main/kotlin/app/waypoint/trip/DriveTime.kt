package app.waypoint.trip

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.serialization.Serializable

/** Area of a drive for the drive-time factor. */
@Serializable
enum class DriveArea { CENTRAL_LONDON, LONDON, ELSEWHERE }

/** Time bucket of a drive for the drive-time factor. */
@Serializable
enum class TimeBucket { WEEKDAY_PEAK, OFF_PEAK }

@Serializable
data class FactorCell(val factor: Double, val samples: Int = 0)

/**
 * Replaces live traffic (spec §4.3). Valhalla doesn't know about traffic, so its London drive
 * times are optimistic. Each completed drive updates the factor for its area × time bucket with an
 * exponentially weighted average, so recent drives count more.
 */
@Serializable
data class DriveTimeCalibration(
    val cells: Map<String, FactorCell> = emptyMap(),
) {
    fun factor(area: DriveArea, bucket: TimeBucket): Double =
        cells[key(area, bucket)]?.factor ?: startingFactor(area, bucket)

    fun cell(area: DriveArea, bucket: TimeBucket): FactorCell =
        cells[key(area, bucket)] ?: FactorCell(startingFactor(area, bucket), 0)

    /** Adjusted drive time = raw Valhalla time × factor. */
    fun adjust(raw: Duration, area: DriveArea, bucket: TimeBucket): Duration =
        (raw.inWholeMilliseconds * factor(area, bucket)).toLong().milliseconds

    /**
     * Records a completed drive. Very short drives and implausible ratios (stops for fuel, detours)
     * are ignored.
     */
    fun record(area: DriveArea, bucket: TimeBucket, planned: Duration, actual: Duration): DriveTimeCalibration {
        if (planned < MIN_DRIVE_TO_LEARN) return this
        val ratio = actual / planned
        if (ratio !in 0.6..3.0) return this
        val old = cell(area, bucket)
        val next = (1 - ALPHA) * old.factor + ALPHA * ratio
        return copy(cells = cells + (key(area, bucket) to FactorCell(next.coerceIn(MIN_FACTOR, MAX_FACTOR), old.samples + 1)))
    }

    fun reset(): DriveTimeCalibration = DriveTimeCalibration()

    companion object {
        const val ALPHA = 0.3
        const val MIN_FACTOR = 0.8
        const val MAX_FACTOR = 2.5
        val MIN_DRIVE_TO_LEARN: Duration = 3.minutes

        private fun key(area: DriveArea, bucket: TimeBucket) = "${area.name}/${bucket.name}"

        /** Central London weekday peaks ×1.4, rest of London ×1.2, elsewhere ×1.0. */
        fun startingFactor(area: DriveArea, bucket: TimeBucket): Double = when (area) {
            DriveArea.CENTRAL_LONDON -> if (bucket == TimeBucket.WEEKDAY_PEAK) 1.4 else 1.2
            DriveArea.LONDON -> 1.2
            DriveArea.ELSEWHERE -> 1.0
        }

        fun areaOf(geometry: List<LatLng>): DriveArea = when {
            geometry.isEmpty() -> DriveArea.ELSEWHERE
            Geo.fractionInside(geometry, London.congestionChargeZone) >= 0.25 -> DriveArea.CENTRAL_LONDON
            Geo.fractionInside(geometry, London.greaterLondon) >= 0.5 -> DriveArea.LONDON
            else -> DriveArea.ELSEWHERE
        }

        fun bucketOf(departAt: Instant): TimeBucket =
            if (London.isWeekdayPeak(departAt)) TimeBucket.WEEKDAY_PEAK else TimeBucket.OFF_PEAK
    }
}

internal fun Double.secondsDuration(): Duration = (this * 1000).toLong().milliseconds

internal fun Duration.roundedToSeconds(): Duration = inWholeSeconds.seconds
