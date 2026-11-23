package app.waypoint.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.waypoint.trip.Leg
import app.waypoint.trip.Segment
import app.waypoint.trip.TransitVehicle
import kotlin.time.Instant

/**
 * The whole leg at a glance, like Google Maps' transit steps: walk 4 min › 9 › 10 › walk 7 min,
 * with the step you're on highlighted and what comes after it underneath. Shown for legs made of
 * more than one step, so a walk to the bus stop doesn't look like the end of the trip.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LegSteps(leg: Leg, current: Int, destination: String?, modifier: Modifier = Modifier) {
    val steps = leg.segments.withIndex().filter { it.value !is Segment.Park }
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FlowRow(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                steps.forEachIndexed { n, (k, s) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (n > 0) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp),
                            )
                        }
                        StepChip(s, done = k < current, now = k == current)
                    }
                }
            }
            nextStep(leg, current, destination)?.let {
                Text(
                    it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun StepChip(s: Segment, done: Boolean, now: Boolean) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier
            .alpha(if (done) 0.38f else 1f)
            .then(if (now) Modifier.background(MaterialTheme.colorScheme.primaryContainer, shape) else Modifier)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (s is Segment.TransitRide) {
            LineBadge(s.line, s.vehicle)
        } else {
            Icon(ModeUi.segmentIcon(s), null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface)
            Text(Format.duration(s.duration), style = MaterialTheme.typography.labelLarge.merge(Numbers))
        }
    }
}

/**
 * What comes after the step you're on: "Next: bus 9 towards Aldwych from Royal Albert Hall · 11:44",
 * "Then walk 7 min to British Museum". On a bus the ride card already says where to get off, so
 * this looks past it.
 */
internal fun nextStep(leg: Leg, current: Int, destination: String?): String? {
    if (current !in leg.segments.indices) return null
    val ahead = leg.segments.drop(current + 1).filter { it !is Segment.Park }
    val ride = ahead.filterIsInstance<Segment.TransitRide>().firstOrNull()
    val walksFirst = ride != null && ahead.first() !== ride
    return when {
        ride != null && walksFirst -> "Then ${verb(ahead.first())} ${Format.duration(ahead.first().duration)} to ${ride.board.name} " +
            "for ${rideName(ride)} · ${Format.clock(ride.departAt)}"
        ride != null -> "Next: ${rideName(ride)} towards ${ride.headsign} from ${ride.board.name} · ${Format.clock(ride.departAt)}"
        ahead.isNotEmpty() && destination != null -> "Then ${ahead.joinToString(", ") { verb(it) + " " + Format.duration(it.duration) }} to $destination"
        else -> null
    }
}

private fun rideName(r: Segment.TransitRide): String = when (r.vehicle) {
    TransitVehicle.BUS -> "bus ${r.line}"
    TransitVehicle.SUBWAY -> if (r.line.contains("line", ignoreCase = true)) r.line else "${r.line} line"
    TransitVehicle.TRAIN -> "${r.line} train"
    TransitVehicle.TRAM -> "${r.line} tram"
}

private fun verb(s: Segment): String = when (s) {
    is Segment.Walk -> "walk"
    is Segment.Drive -> "drive"
    is Segment.Cycle -> "cycle"
    is Segment.TransitRide -> rideName(s)
    is Segment.Park -> "park"
}

/** Whether a leg has more than one step worth showing (walk + bus, drive + park + walk). */
internal fun Leg.hasSteps(): Boolean = segments.count { it !is Segment.Park } > 1

/**
 * When you reach the leg's stop: the end of the current step, then each step after it. A bus or
 * train can't leave before its time, so a quick walk to the stop doesn't make you arrive earlier.
 */
internal fun legArrival(leg: Leg, current: Int, currentEnds: Instant): Instant =
    leg.segments.drop(current + 1).fold(currentEnds) { t, s ->
        if (s is Segment.TransitRide) maxOf(t, s.departAt) + s.duration else t + s.duration
    }
