package app.waypoint.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.waypoint.trip.Continuation
import app.waypoint.trip.FastestOptions
import app.waypoint.trip.Leg
import app.waypoint.trip.ModeChoice
import app.waypoint.trip.OpenIn
import app.waypoint.trip.Origin
import app.waypoint.trip.Stop
import app.waypoint.trip.TimeConstraint
import app.waypoint.trip.TransitVehicle
import app.waypoint.trip.TravelMode
import app.waypoint.trip.Trip
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

@Composable
fun TripBuilderScreen(
    vm: BuilderViewModel,
    dark: Boolean,
    onSearch: (PickTarget) -> Unit,
    onTimeline: () -> Unit,
    onStart: () -> Unit,
    onSaved: () -> Unit,
    onSettings: () -> Unit,
) {
    val trip by vm.trip.collectAsState()
    val planState by vm.plan.collectAsState()
    val here by vm.here.collectAsState()
    val plan = (planState as? PlanState.Ready)?.plan
    val start = (trip.origin as? Origin.Fixed)?.place?.latLng ?: here
    var saveDialog by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(0.3f)) {
            TripMap(Modifier.fillMaxSize(), trip, plan, start, dark, user = here)
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 3.dp, shadowElevation = 2.dp) {
                    Text(
                        trip.name ?: "New trip", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.weight(1f))
                MapButton(Icons.Filled.Bookmarks, "Saved", onSaved)
                Spacer(Modifier.width(8.dp))
                MapButton(Icons.Filled.Settings, "Settings", onSettings)
            }
        }
        LazyColumn(
            Modifier.fillMaxWidth().weight(0.7f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { StartAndTime(vm, trip, onSearch) }
            item { TripModeBar(vm, trip) }
            itemsIndexed(trip.stops, key = { _, s -> s.id }) { i, stop ->
                StopCard(vm, trip, stop, i, plan?.legs?.getOrNull(i), onSearch)
            }
            item {
                OutlinedButton(
                    onClick = { onSearch(PickTarget.AddStop) },
                    enabled = trip.stops.size < Trip.MAX_STOPS,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (trip.stops.size < Trip.MAX_STOPS) "Add stop" else "10 stops is the most a trip can have")
                }
            }
            if (trip.stops.isNotEmpty()) item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { saveDialog = true }) { Text("Save trip") }
                    TextButton(onClick = vm::clear) { Text("Clear") }
                }
            }
        }
        SummaryBar(planState, trip, onTimeline, onStart, vm::replan)
    }

    if (saveDialog) {
        var name by remember { mutableStateOf(trip.name ?: "") }
        AlertDialog(
            onDismissRequest = { saveDialog = false },
            title = { Text("Save trip") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.save(name); saveDialog = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { saveDialog = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MapButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 3.dp, shadowElevation = 2.dp) {
        IconButton(onClick = onClick) { Icon(icon, label) }
    }
}

@Composable
private fun StartAndTime(vm: BuilderViewModel, trip: Trip, onSearch: (PickTarget) -> Unit) {
    var picker by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable { onSearch(PickTarget.Origin) }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Filled.MyLocation, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                SectionLabel("From")
                Text(
                    when (val o = trip.origin) {
                        Origin.CurrentLocation -> "Current location"
                        is Origin.Fixed -> o.place.label
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        val selected = when (trip.time) {
            TimeConstraint.LeaveNow -> 0
            is TimeConstraint.DepartAt -> 1
            is TimeConstraint.ArriveBy -> 2
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("Leave now", "Depart at", "Arrive by").forEachIndexed { i, label ->
                SegmentedButton(
                    selected = selected == i,
                    onClick = {
                        when (i) {
                            0 -> vm.setTime(TimeConstraint.LeaveNow)
                            1 -> picker = "depart"
                            2 -> picker = "arrive"
                        }
                    },
                    shape = SegmentedButtonDefaults.itemShape(i, 3),
                ) {
                    Text(
                        when {
                            i == 1 && trip.time is TimeConstraint.DepartAt -> "Depart ${Format.dayAndClock((trip.time as TimeConstraint.DepartAt).at)}"
                            i == 2 && trip.time is TimeConstraint.ArriveBy -> "Arrive ${Format.dayAndClock((trip.time as TimeConstraint.ArriveBy).at)}"
                            else -> label
                        },
                        maxLines = 1,
                    )
                }
            }
        }
    }
    picker?.let { kind ->
        TimeDialog(
            current = (trip.time as? TimeConstraint.DepartAt)?.at ?: (trip.time as? TimeConstraint.ArriveBy)?.at,
            onDismiss = { picker = null },
            onPick = { at ->
                vm.setTime(if (kind == "depart") TimeConstraint.DepartAt(at) else TimeConstraint.ArriveBy(at))
                picker = null
            },
        )
    }
}

/** Picks a day (today and the next 13 days) and a clock time. */
@Composable
private fun TimeDialog(current: Instant?, onDismiss: () -> Unit, onPick: (Instant) -> Unit) {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now()
    val initial = current?.let { java.time.Instant.ofEpochMilli(it.toEpochMilliseconds()).atZone(zone).toLocalDateTime() }
        ?: java.time.LocalDateTime.now().plusHours(1).withMinute(0)
    var day by remember { mutableStateOf(if (initial.toLocalDate().isBefore(today)) today else initial.toLocalDate()) }
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
    val chosen = day.atTime(state.hour, state.minute)
    val inPast = chosen.isBefore(java.time.LocalDateTime.now().minusMinutes(1))
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(14) { i ->
                        val d = today.plusDays(i.toLong())
                        FilterChip(
                            selected = d == day,
                            onClick = { day = d },
                            label = {
                                Text(
                                    when (i) {
                                        0 -> "Today"
                                        1 -> "Tomorrow"
                                        else -> d.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.UK))
                                    },
                                )
                            },
                        )
                    }
                }
                TimePicker(state)
                if (inPast) Text("That time has already passed.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = !inPast, onClick = {
                onPick(Instant.fromEpochMilliseconds(chosen.atZone(zone).toInstant().toEpochMilli()))
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** All legs one mode, or Fastest with its mode chips (spec §1 "Mode picker"). */
@Composable
private fun TripModeBar(vm: BuilderViewModel, trip: Trip) {
    val mode = trip.defaultMode
    var askStay by remember { mutableStateOf(false) }
    if (askStay) {
        StayPrompt(
            title = "Manual at every stop",
            initial = trip.defaultStay.takeIf { it > Duration.ZERO } ?: Continuation.DEFAULT_MANUAL_STAY,
            onDismiss = { askStay = false },
            onPick = { d -> askStay = false; vm.edit { it.copy(defaultContinuation = Continuation.Manual, defaultStay = d) } },
        )
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("How (all legs)")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                fixedModes.forEach { m ->
                    FilterChip(
                        selected = mode is ModeChoice.Fixed && mode.mode == m,
                        onClick = { vm.setDefaultMode(ModeChoice.Fixed(m)) },
                        label = { Text(ModeUi.label(m)) },
                        leadingIcon = { Icon(ModeUi.icon(m), null, Modifier.size(18.dp)) },
                    )
                }
                FilterChip(
                    selected = mode is ModeChoice.Fastest,
                    onClick = { vm.setDefaultMode(ModeChoice.Fastest(vm.fastestDefaults())) },
                    label = { Text("Fastest") },
                )
            }
            if (mode is ModeChoice.Fastest) {
                FastestChips(mode.options) { vm.setDefaultMode(ModeChoice.Fastest(it)) }
            }
            if (mode is ModeChoice.Fixed && mode.mode == TravelMode.TRANSIT) {
                VehicleChips(mode.transit) { vm.setDefaultMode(mode.copy(transit = it)) }
            }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("At each stop", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                SingleChoiceSegmentedButtonRow {
                    listOf(Continuation.Auto to "Auto", Continuation.Manual to "Manual").forEachIndexed { i, (c, label) ->
                        SegmentedButton(
                            selected = trip.defaultContinuation == c,
                            onClick = {
                                if (c == Continuation.Manual && trip.defaultContinuation != Continuation.Manual) askStay = true
                                else vm.edit { it.copy(defaultContinuation = c) }
                            },
                            shape = SegmentedButtonDefaults.itemShape(i, 2),
                        ) { Text(label) }
                    }
                }
            }
        }
    }
}

@Composable
fun FastestChips(options: FastestOptions, onChange: (FastestOptions) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(options.walk, { onChange(options.copy(walk = !options.walk)) }, { Text("Walk") })
        for (v in listOf(TransitVehicle.BUS, TransitVehicle.TRAIN, TransitVehicle.SUBWAY, TransitVehicle.TRAM)) {
            val on = v in options.transit
            FilterChip(on, { onChange(options.copy(transit = if (on) options.transit - v else options.transit + v)) }, { Text(ModeUi.vehicleLabel(v)) })
        }
        FilterChip(options.cycle, { onChange(options.copy(cycle = !options.cycle)) }, { Text("Cycling") })
        FilterChip(options.drive, { onChange(options.copy(drive = !options.drive)) }, { Text("Drive") })
    }
    if (options.drive) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Avoid the Congestion Charge zone", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(options.avoidChargeZone, { onChange(options.copy(avoidChargeZone = it)) })
        }
    }
}

@Composable
private fun VehicleChips(selected: Set<TransitVehicle>, onChange: (Set<TransitVehicle>) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (v in listOf(TransitVehicle.BUS, TransitVehicle.TRAIN, TransitVehicle.SUBWAY, TransitVehicle.TRAM)) {
            val on = v in selected
            FilterChip(on, { onChange(if (on) selected - v else selected + v) }, { Text(ModeUi.vehicleLabel(v)) })
        }
    }
}

@Composable
private fun StopCard(vm: BuilderViewModel, trip: Trip, stop: Stop, index: Int, leg: Leg?, onSearch: (PickTarget) -> Unit) {
    val isLast = index == trip.stops.lastIndex
    var menu by remember { mutableStateOf(false) }
    var askStay by remember { mutableStateOf(false) }
    if (askStay) {
        StayPrompt(
            title = "Manual at ${stop.place.label}",
            initial = stop.stay?.takeIf { it > Duration.ZERO } ?: Continuation.DEFAULT_MANUAL_STAY,
            onDismiss = { askStay = false },
            onPick = { d -> askStay = false; vm.setManual(stop.id, d) },
        )
    }
    val context = LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StopBadge(index + 1)
                Column(Modifier.weight(1f).clickable { onSearch(PickTarget.ReplaceStop(stop.id)) }) {
                    Text(stop.place.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    stop.place.address?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Stop options") }
                    DropdownMenu(menu, { menu = false }) {
                        if (index > 0) DropdownMenuItem({ Text("Move up") }, { vm.moveStop(stop.id, -1); menu = false })
                        if (!isLast) DropdownMenuItem({ Text("Move down") }, { vm.moveStop(stop.id, 1); menu = false })
                        DropdownMenuItem({ Text("Change place") }, { onSearch(PickTarget.ReplaceStop(stop.id)); menu = false })
                        DropdownMenuItem({ Text("Save as favourite") }, { vm.favourite(stop.place, stop.arrivalPoint); menu = false })
                        DropdownMenuItem({ Text("Open in Google Maps from here") }, {
                            menu = false
                            val remaining = trip.stops.drop(index).map { OpenIn.Target(it.place.latLng, it.place.label) }
                            openUri(context, OpenIn.googleMaps(null, remaining, leg?.mode ?: TravelMode.DRIVE))
                        })
                        DropdownMenuItem({ Text("Remove") }, { vm.removeStop(stop.id); menu = false }, leadingIcon = { Icon(Icons.Filled.Close, null) })
                    }
                }
            }
            leg?.let { LegLine(it, vm, index) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                MenuChip(
                    label = "To here: ${modeChoiceLabel(stop.modeToHere)}",
                    selected = stop.modeToHere != null,
                    icon = modeChoiceIcon(stop.modeToHere),
                    options = listOf(MenuOption<ModeChoice?>("Trip default", null)) +
                        fixedModes.map { MenuOption<ModeChoice?>(ModeUi.label(it), ModeChoice.Fixed(it), ModeUi.icon(it)) } +
                        MenuOption<ModeChoice?>("Fastest", ModeChoice.Fastest(vm.fastestDefaults())),
                    onPick = { m -> vm.updateStop(stop.id) { it.copy(modeToHere = m) } },
                )
                if (!isLast) {
                    val c = trip.continuationAt(stop)
                    FilterChip(
                        selected = c == Continuation.Manual,
                        onClick = {
                            if (c == Continuation.Manual) vm.setContinuation(stop.id, Continuation.Auto) else askStay = true
                        },
                        label = { Text(if (c == Continuation.Manual) "Manual" else "Auto") },
                    )
                    val stay = trip.stayAt(stop)
                    MenuChip(
                        label = if (stay == Duration.ZERO) "No stop" else "Stay ${Format.duration(stay)}",
                        selected = stop.stay != null,
                        icon = Icons.Filled.Schedule,
                        options = listOf(Duration.ZERO, 5.minutes, 15.minutes, 30.minutes, 60.minutes, 120.minutes).map {
                            MenuOption(if (it == Duration.ZERO) "Drive straight through" else Format.duration(it), it)
                        },
                        onPick = { d -> vm.updateStop(stop.id) { it.copy(stay = d) } },
                    )
                }
                val mode = trip.modeTo(stop)
                val drives = (mode as? ModeChoice.Fixed)?.mode == TravelMode.DRIVE || (mode as? ModeChoice.Fastest)?.options?.drive == true
                if (drives) {
                    FilterChip(
                        selected = stop.arrivalPoint != null,
                        onClick = {
                            if (stop.arrivalPoint != null) vm.updateStop(stop.id) { it.copy(arrivalPoint = null) }
                            else onSearch(PickTarget.CarPark(stop.id))
                        },
                        label = { Text(if (stop.arrivalPoint != null) "Car park set" else "Navigate to car park") },
                        leadingIcon = { Icon(Icons.Filled.LocalParking, null, Modifier.size(18.dp)) },
                    )
                }
                val cycles = (mode as? ModeChoice.Fixed)?.mode == TravelMode.CYCLE || (mode as? ModeChoice.Fastest)?.options?.cycle == true
                if (!isLast && (drives || cycles)) {
                    FilterChip(
                        selected = stop.leaveVehicle,
                        onClick = { vm.updateStop(stop.id) { it.copy(leaveVehicle = !it.leaveVehicle) } },
                        label = { Text(if (drives) "Park here" else "Lock bike here") },
                    )
                }
            }
        }
    }
}

@Composable
private fun LegLine(leg: Leg, vm: BuilderViewModel, index: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(ModeUi.icon(leg.mode), null, tint = ModeUi.color(leg.mode), modifier = Modifier.size(18.dp))
            Text(
                buildString {
                    if (leg.chosenByFastest) append("Fastest: ")
                    append(ModeUi.label(leg.mode)).append(" · ").append(Format.duration(leg.duration))
                    append(" · arrive ").append(Format.clock(leg.arriveAt))
                },
                style = MaterialTheme.typography.bodyMedium.merge(Numbers),
            )
        }
        leg.segments.filterIsInstance<app.waypoint.trip.Segment.TransitRide>().takeIf { it.isNotEmpty() }?.let { rides ->
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { rides.forEach { LineBadge(it.line, it.vehicle) } }
        }
        leg.runnerUp?.let { ru ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${ModeUi.label(ru.mode)} would take ${Format.duration(ru.duration)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { vm.useRunnerUp(index) }) { Text("Use ${ModeUi.label(ru.mode).lowercase()}") }
            }
        }
        leg.warnings.filterNot { ModeUi.isInfo(it) }.forEach { WarningLine(it) }
    }
}

@Composable
private fun SummaryBar(state: PlanState, trip: Trip, onTimeline: () -> Unit, onStart: () -> Unit, onRetry: () -> Unit) {
    Surface(tonalElevation = 4.dp, shadowElevation = 8.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state) {
                PlanState.Empty -> Text("Add the places you want to go, in your order.", style = MaterialTheme.typography.bodyMedium)
                PlanState.Planning -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Planning ${trip.stops.size} stop${if (trip.stops.size == 1) "" else "s"}…")
                }
                is PlanState.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(state.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    IconButton(onClick = onRetry) { Icon(Icons.Filled.Refresh, "Try again") }
                }
                is PlanState.Ready -> {
                    val p = state.plan
                    Row(verticalAlignment = Alignment.Bottom) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${Format.clock(p.departAt)} → ${Format.clock(p.arriveAt)}",
                                style = MaterialTheme.typography.headlineSmall.merge(Numbers),
                            )
                            Text(
                                "${Format.duration(p.arriveAt - p.departAt)} · ${p.legs.size} stop${if (p.legs.size == 1) "" else "s"}" +
                                    (p.arriveBySlack?.let { s -> if (s.isNegative()) " · ${Format.duration(-s)} late" else " · leave by ${Format.clock(p.latestDeparture!!)}" } ?: ""),
                                style = MaterialTheme.typography.bodyMedium.merge(Numbers),
                                color = if (p.arriveBySlack?.isNegative() == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    p.allWarnings.filterNot { ModeUi.isInfo(it) }.distinct().forEach { WarningLine(it) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = onTimeline, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Timeline, null); Spacer(Modifier.width(6.dp)); Text("Timeline")
                        }
                        Button(onClick = onStart, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Navigation, null); Spacer(Modifier.width(6.dp)); Text("Start")
                        }
                    }
                }
            }
        }
    }
}

fun openUri(context: android.content.Context, uri: String) {
    runCatching {
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(uri)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        android.widget.Toast.makeText(context, "No app can open that link", android.widget.Toast.LENGTH_SHORT).show()
    }
}
