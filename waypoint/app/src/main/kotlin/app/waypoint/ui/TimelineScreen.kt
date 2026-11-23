package app.waypoint.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.waypoint.graph
import app.waypoint.trip.Leg
import app.waypoint.trip.Plan
import app.waypoint.trip.Segment
import androidx.compose.ui.platform.LocalContext
import kotlin.time.Duration

/** Trip timeline (spec §6.2): legs with segments, lines, docks, parking, warnings and runner-ups. */
@Composable
fun TimelineScreen(vm: BuilderViewModel, onBack: () -> Unit) {
    val state by vm.plan.collectAsState()
    val plan = (state as? PlanState.Ready)?.plan
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Timeline") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        if (plan == null) {
            Text("Plan a trip first.", Modifier.padding(pad).padding(16.dp))
        } else {
            TimelineList(plan, Modifier.padding(pad), onRunnerUp = vm::useRunnerUp)
        }
    }
}

@Composable
fun TimelineList(plan: Plan, modifier: Modifier = Modifier, onRunnerUp: ((Int) -> Unit)? = null) {
    val units = LocalContext.current.graph.settings.current.units
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            TimeRow(Format.clock(plan.departAt), "Start", bold = true)
        }
        itemsIndexed(plan.legs) { i, leg ->
            val stop = plan.trip.stops[i]
            LegBlock(leg, units)
            leg.runnerUp?.let { ru ->
                Row(Modifier.padding(start = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Runner-up: ${ModeUi.label(ru.mode)}, ${Format.duration(ru.duration)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (onRunnerUp != null) TextButton(onClick = { onRunnerUp(i) }) { Text("Use") }
                }
            }
            Column(Modifier.padding(start = 64.dp)) { leg.warnings.forEach { WarningLine(it) } }
            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(Format.clock(leg.arriveAt), Modifier.width(52.dp), style = MaterialTheme.typography.titleMedium.merge(Numbers))
                StopBadge(i + 1, 24.dp)
                Column(Modifier.padding(start = 10.dp)) {
                    Text(stop.place.label, style = MaterialTheme.typography.titleMedium)
                    val stay = plan.trip.stayAt(stop)
                    if (i < plan.legs.lastIndex) {
                        val manual = plan.trip.continuationAt(stop) == app.waypoint.trip.Continuation.Manual
                        Text(
                            (if (manual) "Manual" else "Auto") + if (stay > Duration.ZERO) " · stay ${Format.duration(stay)}" else " · straight on",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        plan.arriveBySlack?.let { slack ->
            item {
                Text(
                    if (slack.isNegative()) "${Format.duration(-slack)} later than your Arrive-by time"
                    else "${Format.duration(slack)} to spare · leave by ${Format.clock(plan.latestDeparture!!)}",
                    color = if (slack.isNegative()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
        item {
            Text(
                "Drive times use your drive-time factor, not live traffic. Transit: Powered by TfL Open Data / Transitous. © OpenStreetMap contributors.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

@Composable
private fun TimeRow(time: String, text: String, bold: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(time, Modifier.width(52.dp), style = MaterialTheme.typography.titleMedium.merge(Numbers))
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = if (bold) FontWeight.SemiBold else null)
    }
}

/** One leg: a coloured rail with its segments. */
@Composable
private fun LegBlock(leg: Leg, units: app.waypoint.trip.DistanceUnits) {
    Row(Modifier.height(IntrinsicSize.Min)) {
        Box(Modifier.width(52.dp))
        Box(Modifier.width(24.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(ModeUi.color(leg.mode)))
        }
        Column(Modifier.padding(start = 10.dp, top = 4.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "${if (leg.chosenByFastest) "Fastest: " else ""}${ModeUi.label(leg.mode)} · ${Format.duration(leg.duration)}",
                style = MaterialTheme.typography.labelLarge, color = ModeUi.color(leg.mode),
            )
            leg.segments.forEach { SegmentLine(it, units) }
        }
    }
}

@Composable
private fun SegmentLine(s: Segment, units: app.waypoint.trip.DistanceUnits) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(ModeUi.segmentIcon(s), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        when (s) {
            is Segment.Walk -> Text("Walk ${Format.duration(s.duration)}" + if (s.distanceMeters > 0) " · ${Format.distance(s.distanceMeters, units)}" else "", style = MaterialTheme.typography.bodyMedium)
            is Segment.Drive -> Text(
                "Drive ${Format.duration(s.adjustedDuration)} · ${Format.distance(s.route.distanceMeters, units)}" +
                    if (s.adjustedDuration != s.rawDuration) " (router says ${Format.duration(s.rawDuration)})" else "",
                style = MaterialTheme.typography.bodyMedium,
            )
            is Segment.Cycle -> Text("Cycle ${Format.duration(s.duration)} · ${Format.distance(s.route.distanceMeters, units)}", style = MaterialTheme.typography.bodyMedium)
            is Segment.Park -> Text("Park ${Format.duration(s.duration)}", style = MaterialTheme.typography.bodyMedium)
            is Segment.TransitRide -> Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LineBadge(s.line, s.vehicle)
                    Text("to ${s.headsign}", style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    "${Format.clock(s.departAt)} ${s.board.name} → ${Format.clock(s.arriveAt)} ${s.alight.name}",
                    style = MaterialTheme.typography.bodySmall.merge(Numbers), color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is Segment.BikePickup -> Text("Pick up at ${s.dock.name} · ${s.dock.bikesAvailable} bikes", style = MaterialTheme.typography.bodyMedium)
            is Segment.BikeDropoff -> Text("Dock at ${s.dock.name} · ${s.dock.spacesAvailable} spaces", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
