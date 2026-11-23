package app.waypoint.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import app.waypoint.graph
import app.waypoint.music.MusicController
import app.waypoint.music.TripStartMusic
import app.waypoint.trip.Continuation
import app.waypoint.trip.DistanceUnits
import app.waypoint.trip.DriveArea
import app.waypoint.trip.TimeBucket
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** Settings (spec §6.7). */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    val s by graph.settings.settings.collectAsState()
    val calibration by graph.calibration.collectAsState()
    val musicAccess by graph.music.access.collectAsState()
    LaunchedEffect(Unit) { graph.music.refreshAccess() }
    fun update(change: (app.waypoint.data.AppSettings) -> app.waypoint.data.AppSettings) = graph.settings.update(change)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Section("Routing") {
                var stadia by remember { mutableStateOf(s.stadiaApiKey) }
                OutlinedTextField(
                    stadia, { stadia = it; update { st -> st.copy(stadiaApiKey = it.trim()) } },
                    label = { Text("Stadia Maps API key") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    if (s.usesStadia) "Routing with Stadia Maps (free plan, non-commercial)."
                    else "No key: routing with the public FOSSGIS Valhalla server (fair use; can't avoid the charge zone).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                var tfl by remember { mutableStateOf(s.tflAppKey) }
                OutlinedTextField(
                    tfl, { tfl = it; update { st -> st.copy(tflAppKey = it.trim()) } },
                    label = { Text("TfL app key (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Toggle("Avoid tolls", s.avoidTolls) { v -> update { it.copy(avoidTolls = v) } }
                Toggle("Avoid motorways", s.avoidMotorways) { v -> update { it.copy(avoidMotorways = v) } }
                Toggle("Avoid ferries", s.avoidFerries) { v -> update { it.copy(avoidFerries = v) } }
                Toggle("My car meets the ULEZ standards", s.ulezCompliantCar) { v -> update { it.copy(ulezCompliantCar = v) } }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Distances", Modifier.weight(1f))
                    SingleChoiceSegmentedButtonRow {
                        listOf(DistanceUnits.MILES to "Miles", DistanceUnits.KILOMETERS to "Km").forEachIndexed { i, (u, l) ->
                            SegmentedButton(s.units == u, { update { it.copy(units = u) } }, SegmentedButtonDefaults.itemShape(i, 2)) { Text(l) }
                        }
                    }
                }
            }

            Section("At each stop") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("New trips continue", Modifier.weight(1f))
                    SingleChoiceSegmentedButtonRow {
                        listOf(Continuation.Auto to "Auto", Continuation.Manual to "Manual").forEachIndexed { i, (c, l) ->
                            SegmentedButton(s.defaultContinuation == c, { update { it.copy(defaultContinuation = c) } }, SegmentedButtonDefaults.itemShape(i, 2)) { Text(l) }
                        }
                    }
                }
                DurationRow("Default stay", s.defaultStay, listOf(Duration.ZERO, 5.minutes, 15.minutes, 30.minutes, 60.minutes)) { d -> update { it.copy(defaultStay = d) } }
                DurationRow("Parking time after a drive", s.defaultParkingTime, listOf(Duration.ZERO, 3.minutes, 5.minutes, 10.minutes, 15.minutes)) { d -> update { it.copy(defaultParkingTime = d) } }
            }

            Section("Fastest defaults") {
                FastestChips(s.fastestDefaults) { o -> update { it.copy(fastestDefaults = o) } }
            }

            Section("Drive-time factors") {
                Text(
                    "Valhalla doesn't know live traffic, so drive times are multiplied by these factors. Each finished drive tunes them.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                for (area in DriveArea.entries) for (bucket in TimeBucket.entries) {
                    val cell = calibration.cell(area, bucket)
                    Row {
                        Text(
                            "${areaLabel(area)} · ${if (bucket == TimeBucket.WEEKDAY_PEAK) "weekday peaks" else "other times"}",
                            Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                        )
                        Text("×%.2f".format(java.util.Locale.UK, cell.factor), style = MaterialTheme.typography.bodyMedium.merge(Numbers))
                        Text(" (${cell.samples})", style = MaterialTheme.typography.bodySmall.merge(Numbers), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                OutlinedButton(onClick = graph::resetCalibration) { Text("Reset to starting factors") }
            }

            Section("Bike share") {
                Toggle("Santander Cycles (TfL)", s.santanderCycles) { v -> update { it.copy(santanderCycles = v) } }
                var feeds by remember { mutableStateOf(s.gbfsFeeds.joinToString("\n")) }
                OutlinedTextField(
                    feeds,
                    { feeds = it; update { st -> st.copy(gbfsFeeds = it.lines().map(String::trim).filter(String::isNotEmpty)) } },
                    label = { Text("Other systems: GBFS gbfs.json URLs, one per line") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(), minLines = 2,
                )
            }

            Section("Voice and music") {
                Toggle("Voice guidance", s.voiceGuidance) { v -> update { it.copy(voiceGuidance = v) } }
                Toggle("Speak Auto countdowns", s.speakCountdowns) { v -> update { it.copy(speakCountdowns = v) } }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Music controls")
                        Text(
                            if (musicAccess) "On: the chip controls whatever is playing." else "Off: the chip only opens your player.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { context.startActivity(MusicController.notificationAccessIntent()) }) { Text(if (musicAccess) "Manage" else "Allow") }
                }
                val players = remember { graph.music.installedPlayers() }
                if (players.isNotEmpty()) {
                    MenuChip(
                        label = "Default player: " + (players.firstOrNull { it.packageName == s.defaultMusicPackage }?.name ?: "system default"),
                        selected = s.defaultMusicPackage != null,
                        options = listOf(MenuOption<String?>("System default", null)) + players.map { MenuOption<String?>(it.name, it.packageName) },
                        onPick = { p -> update { it.copy(defaultMusicPackage = p) } },
                    )
                }
            }

            Section("Music when a trip starts") {
                var choosing by remember { mutableStateOf(false) }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(TripStartMusic.NOTHING to "Nothing", TripStartMusic.RESUME to "Resume", TripStartMusic.PLAYLIST to "Playlist").forEachIndexed { i, (m, l) ->
                        SegmentedButton(s.tripStartMusic == m, { update { it.copy(tripStartMusic = m) } }, SegmentedButtonDefaults.itemShape(i, 3)) { Text(l) }
                    }
                }
                if (s.tripStartMusic == TripStartMusic.PLAYLIST) {
                    val players = remember { graph.music.installedPlayers() }
                    val player = players.firstOrNull { it.packageName == s.defaultMusicPackage }
                    if (player == null) {
                        Text(
                            "Choose a default player above first; the playlist comes from its library.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                s.tripStartPlaylist?.takeIf { it.packageName == player.packageName }?.title ?: "No playlist chosen",
                                Modifier.weight(1f),
                            )
                            TextButton(onClick = { choosing = true }) { Text("Choose") }
                        }
                        if (choosing) {
                            PlaylistPicker(
                                library = graph.music.library,
                                packageName = player.packageName,
                                playerName = player.name,
                                onDismiss = { choosing = false },
                                onPick = { c -> choosing = false; update { it.copy(tripStartPlaylist = c) } },
                            )
                        }
                    }
                }
                Text(
                    "Resume plays whatever you listened to last. If a player won't let other apps open its playlists, Waypoint resumes it instead.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Section("Testing") {
                Toggle("Simulate location (follow the route without moving)", s.simulateLocation) { v -> update { it.copy(simulateLocation = v) } }
            }

            Text(
                "Maps © OpenStreetMap contributors · tiles OpenFreeMap · routing Valhalla via ${graph.routeEndpoint.name} · " +
                    "search Photon by komoot · Powered by TfL Open Data · transit elsewhere Transitous · navigation Ferrostar.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun areaLabel(a: DriveArea) = when (a) {
    DriveArea.CENTRAL_LONDON -> "Central London"
    DriveArea.LONDON -> "Rest of London"
    DriveArea.ELSEWHERE -> "Elsewhere"
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel(title)
            content()
        }
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(value, onChange)
    }
}

@Composable
private fun DurationRow(label: String, value: Duration, options: List<Duration>, onPick: (Duration) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        MenuChip(
            label = if (value == Duration.ZERO) "None" else Format.duration(value),
            selected = true,
            options = options.map { MenuOption(if (it == Duration.ZERO) "None" else Format.duration(it), it) },
            onPick = onPick,
        )
    }
}
