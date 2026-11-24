package app.waypoint.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.waypoint.trip.Leg
import app.waypoint.trip.LegOption
import app.waypoint.trip.Segment
import app.waypoint.trip.transitRoute
import kotlin.time.Duration

/**
 * The public transport routes for a leg (up to three, fastest first), like Google Maps' list of
 * journeys: the lines you'd take, how long, when the first one leaves and when you'd arrive. The
 * one the plan uses is highlighted; tap another to take it instead.
 */
@Composable
fun TransitOptions(leg: Leg, onPick: (route: String) -> Unit) {
    val current = transitRoute(leg.segments)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Public transport options", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        leg.transitOptions.forEach { option ->
            val route = transitRoute(option.segments)
            val selected = route == current
            Surface(
                onClick = { if (!selected) onPick(route) },
                shape = RoundedCornerShape(12.dp),
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                border = if (selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Lines(option)
                        val walk = option.segments.filterIsInstance<Segment.Walk>().fold(Duration.ZERO) { a, w -> a + w.duration }
                        val leaves = option.segments.filterIsInstance<Segment.TransitRide>().firstOrNull()?.departAt
                        Text(
                            listOfNotNull(
                                leaves?.let { "leaves ${Format.clock(it)}" },
                                "walk ${Format.duration(walk)}".takeIf { walk > Duration.ZERO },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall.merge(Numbers),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(Format.duration(option.duration), style = MaterialTheme.typography.titleSmall.merge(Numbers))
                        Text(
                            "arrive ${Format.clock(option.arriveAt)}",
                            style = MaterialTheme.typography.bodySmall.merge(Numbers),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** The lines in order: 9 › 10, or a walking figure when there's no ride. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Lines(option: LegOption) {
    val rides = option.segments.filterIsInstance<Segment.TransitRide>()
    FlowRow(verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        if (rides.isEmpty()) Icon(Icons.AutoMirrored.Filled.DirectionsWalk, "Walk", Modifier.size(18.dp))
        rides.forEachIndexed { i, r ->
            if (i > 0) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            LineBadge(r.line, r.vehicle)
        }
    }
}
