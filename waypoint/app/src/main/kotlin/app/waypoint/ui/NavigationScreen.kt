package app.waypoint.ui

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.waypoint.graph
import app.waypoint.music.MusicController
import app.waypoint.session.SegmentUi
import app.waypoint.session.SessionState
import app.waypoint.session.TripSession
import app.waypoint.session.UserAction
import app.waypoint.trip.Dock
import app.waypoint.trip.OpenIn
import app.waypoint.trip.Segment
import app.waypoint.trip.StopWait
import app.waypoint.trip.TripPhase
import com.stadiamaps.ferrostar.maplibreui.views.DynamicallyOrientingNavigationView
import kotlin.time.Clock
import org.maplibre.compose.style.BaseStyle

@Composable
fun NavigationScreen(session: TripSession, music: MusicController, dark: Boolean, onTimeline: () -> Unit, onExit: () -> Unit) {
    val state by session.state.collectAsState()
    var confirmEnd by remember { mutableStateOf(false) }
    val plan = state.plan

    if (plan == null) {
        LaunchedEffect(Unit) { onExit() }
        return
    }
    if (state.phase is TripPhase.Finished) {
        TripComplete(state, onDone = { session.endTrip(); onExit() })
        return
    }

    Column(Modifier.fillMaxSize()) {
        TripTopBar(session, state, music, onTimeline, onEnd = { confirmEnd = true })
        Box(Modifier.fillMaxWidth().weight(1f)) {
            val seg = state.segment
            if (seg is SegmentUi.Guiding && state.atStop == null) {
                DynamicallyOrientingNavigationView(
                    modifier = Modifier.fillMaxSize(),
                    baseStyle = BaseStyle.Uri(if (dark) MapStyles.NIGHT else MapStyles.DAY),
                    viewModel = session.navigationViewModel,
                    onTapExit = { confirmEnd = true },
                )
            } else {
                val focus = state.legIndex ?: state.atStop?.let { it.stopIndex + 1 }?.takeIf { it < plan.legs.size }
                val docks = when (seg) {
                    is SegmentUi.BikePickup -> listOf(seg.dock)
                    is SegmentUi.BikeDropoff -> listOf(seg.dock)
                    else -> emptyList()
                }
                TripMap(Modifier.fillMaxSize(), plan.trip, plan, plan.start, dark, user = state.userLocation, docks = docks, focusLeg = focus, bottomPadding = 220)
                Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp)) {
                    when {
                        state.atStop != null -> ArrivalCard(session, state)
                        seg is SegmentUi.TransitWait -> TransitWaitCard(session, seg)
                        seg is SegmentUi.TransitRide -> TransitRideCard(session, seg)
                        seg is SegmentUi.BikePickup -> BikePickupCard(session, seg.dock)
                        seg is SegmentUi.BikeDropoff -> BikeDropoffCard(session, seg.dock)
                        seg is SegmentUi.Preparing -> InfoCard { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.size(12.dp)); Text(seg.text) } }
                        else -> Unit
                    }
                }
            }
            state.message?.let { msg ->
                Surface(
                    Modifier.align(Alignment.TopCenter).padding(12.dp),
                    shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.inverseSurface, shadowElevation = 4.dp,
                ) {
                    Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(msg, color = MaterialTheme.colorScheme.inverseOnSurface, modifier = Modifier.widthIn(max = 280.dp), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = session::dismissMessage) { Text("OK", color = MaterialTheme.colorScheme.inversePrimary) }
                    }
                }
            }
        }
    }

    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text("End this trip?") },
            text = { Text("Guidance stops. The trip stays in the builder.") },
            confirmButton = { TextButton(onClick = { confirmEnd = false; session.endTrip(); onExit() }) { Text("End trip") } },
            dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("Keep going") } },
        )
    }
}

/** "Stop 2 of 5" + music chip + overflow (Open in…, mute, End trip). */
@Composable
private fun TripTopBar(session: TripSession, state: SessionState, music: MusicController, onTimeline: () -> Unit, onEnd: () -> Unit) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val muted by session.tts.muteState.collectAsState()
    Surface(tonalElevation = 2.dp, shadowElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StopBadge(state.stopNumber, 30.dp)
            Column(Modifier.weight(1f)) {
                Text("Stop ${state.stopNumber} of ${state.stopCount}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(state.stopName ?: "", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            MusicChip(music)
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Trip options") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (muted) "Unmute voice" else "Mute voice") },
                        leadingIcon = { Icon(if (muted) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff, null) },
                        onClick = { menu = false; session.tts.setMuted(!muted) },
                    )
                    DropdownMenuItem({ Text("Timeline") }, { menu = false; onTimeline() })
                    remainingTargets(state).takeIf { it.isNotEmpty() }?.let { remaining ->
                        val mode = state.plan?.legs?.getOrNull(state.stopNumber - 1)?.mode ?: app.waypoint.trip.TravelMode.DRIVE
                        DropdownMenuItem({ Text("Open in Google Maps") }, { menu = false; openUri(context, OpenIn.googleMaps(state.userLocation, remaining, mode)) })
                        DropdownMenuItem({ Text("Open in Organic Maps") }, { menu = false; openUri(context, OpenIn.organicMaps(state.userLocation, remaining.first(), mode)) })
                        DropdownMenuItem({ Text("Open in OsmAnd") }, { menu = false; openUri(context, OpenIn.osmAnd(remaining.first(), mode)) })
                    }
                    DropdownMenuItem({ Text("End trip") }, { menu = false; onEnd() })
                }
            }
        }
    }
}

private fun remainingTargets(state: SessionState): List<OpenIn.Target> {
    val plan = state.plan ?: return emptyList()
    val from = when (val p = state.phase) {
        is TripPhase.OnLeg -> p.legIndex
        is TripPhase.AtStop -> p.stopIndex + 1
        else -> 0
    }
    return plan.trip.stops.drop(from).map { OpenIn.Target(it.place.latLng, it.place.label) }
}

/** Mini-player (spec §5): title / artist / art, play-pause, next. Long-press to switch player. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MusicChip(music: MusicController) {
    val now by music.nowPlaying.collectAsState()
    val access by music.access.collectAsState()
    var picker by remember { mutableStateOf(false) }
    var explain by remember { mutableStateOf(false) }
    val context = LocalContext.current
    LaunchedEffect(Unit) { music.refreshAccess() }

    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.combinedClickable(
            onClick = { if (now == null) music.launchPlayer() else music.launchPlayer(now!!.packageName) },
            onLongClick = { picker = true },
        ),
    ) {
        Row(Modifier.padding(start = 6.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            val art = now?.art
            if (art != null) {
                Image(art.asImageBitmap(), null, Modifier.size(26.dp).clip(RoundedCornerShape(50)))
            } else {
                Icon(Icons.Filled.MusicNote, null, Modifier.size(20.dp))
            }
            Text(
                now?.title ?: if (access) "Music" else "Music",
                style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 6.dp).widthIn(max = 90.dp),
            )
            if (access && now != null) {
                IconButton(onClick = music::playPause, Modifier.size(34.dp)) {
                    Icon(if (now!!.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (now!!.isPlaying) "Pause" else "Play")
                }
                IconButton(onClick = music::next, Modifier.size(34.dp)) { Icon(Icons.Filled.SkipNext, "Next track") }
            } else if (!access) {
                TextButton(onClick = { explain = true }) { Text("Controls", style = MaterialTheme.typography.labelSmall) }
            }
        }
    }

    if (picker) {
        val players = remember { music.installedPlayers() }
        AlertDialog(
            onDismissRequest = { picker = false },
            title = { Text("Default music player") },
            text = {
                Column {
                    players.forEach { p ->
                        TextButton(onClick = {
                            context.graph.settings.update { it.copy(defaultMusicPackage = p.packageName) }
                            picker = false
                            music.launchPlayer(p.packageName)
                        }) { Text(p.name) }
                    }
                    if (players.isEmpty()) Text("No music apps found.")
                }
            },
            confirmButton = { TextButton(onClick = { picker = false }) { Text("Close") } },
        )
    }
    if (explain) {
        AlertDialog(
            onDismissRequest = { explain = false },
            title = { Text("Control your music from here") },
            text = {
                Text(
                    "To show what's playing and add play/pause and next, Waypoint needs notification access. " +
                        "It only reads the media session of your player; nothing leaves the phone.\n\n" +
                        "If the switch is greyed out: App info → ⋮ → Allow restricted settings, then try again.",
                )
            },
            confirmButton = {
                TextButton(onClick = { explain = false; context.startActivity(MusicController.notificationAccessIntent()) }) { Text("Open settings") }
            },
            dismissButton = { TextButton(onClick = { explain = false }) { Text("Not now") } },
        )
    }
}

@Composable
private fun InfoCard(content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

/** Arrival card (spec §4.2): countdown (Auto) or Start next leg / Skip next stop (Manual). */
@Composable
private fun ArrivalCard(session: TripSession, state: SessionState) {
    val at = state.atStop ?: return
    val plan = state.plan ?: return
    val stop = plan.trip.stops[at.stopIndex]
    val nextLeg = plan.legs.getOrNull(at.stopIndex + 1)
    InfoCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
            Text("Arrived at ${stop.place.label}", style = MaterialTheme.typography.titleMedium)
        }
        if (nextLeg != null) {
            Text(
                "Next: ${ModeUi.label(nextLeg.mode)} ${Format.duration(nextLeg.duration)} to ${state.nextStopName}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        when (val w = at.wait) {
            is StopWait.Countdown -> {
                Text(
                    "Continuing to ${state.nextStopName} in ${Format.countdown(state.countdownLeft ?: (w.until - Clock.System.now()))}",
                    style = MaterialTheme.typography.headlineSmall.merge(Numbers), color = MaterialTheme.colorScheme.primary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = session::startNextLeg, Modifier.weight(1f)) { Text("Start now") }
                    OutlinedButton(onClick = session::pauseCountdown, Modifier.weight(1f)) { Text("Pause") }
                }
            }
            StopWait.ForYou -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = session::startNextLeg, Modifier.weight(1f)) { Text("Start next leg") }
                if (plan.legs.size > at.stopIndex + 2) {
                    OutlinedButton(onClick = session::skipNextStop, Modifier.weight(1f)) { Text("Skip next stop") }
                }
            }
        }
    }
}

@Composable
private fun TransitWaitCard(session: TripSession, seg: SegmentUi.TransitWait) {
    val ride = seg.ride
    InfoCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LineBadge(ride.line, ride.vehicle)
            Text("to ${ride.headsign}", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("Board at ${ride.board.name} · ${Format.clock(ride.departAt)}", style = MaterialTheme.typography.bodyMedium.merge(Numbers))
        if (seg.arrivals.isNotEmpty()) {
            val now = Clock.System.now()
            Text(
                "Live: " + seg.arrivals.joinToString(" · ") { a ->
                    val m = (a.expected - now).inWholeMinutes
                    if (m <= 0) "due" else "$m min"
                },
                style = MaterialTheme.typography.titleSmall.merge(Numbers), color = MaterialTheme.colorScheme.primary,
            )
        }
        Text("Get off at ${ride.alight.name} · ${Format.clock(ride.arriveAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { session.confirm(UserAction.ON_BOARD) }, Modifier.fillMaxWidth()) { Text("I'm on board") }
    }
}

@Composable
private fun TransitRideCard(session: TripSession, seg: SegmentUi.TransitRide) {
    val ride = seg.ride
    InfoCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LineBadge(ride.line, ride.vehicle)
            Text("to ${ride.headsign}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (seg.getOffNext) {
            Text("Get off at the next stop", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
        }
        Text("Get off at ${ride.alight.name} · ${Format.clock(ride.arriveAt)}", style = MaterialTheme.typography.titleMedium.merge(Numbers))
        OutlinedButton(onClick = { session.confirm(UserAction.OFF_VEHICLE) }, Modifier.fillMaxWidth()) { Text("I'm off") }
    }
}

@Composable
private fun BikePickupCard(session: TripSession, dock: Dock) {
    val context = LocalContext.current
    InfoCard {
        Text("Unlock a bike", style = MaterialTheme.typography.titleMedium)
        Text("${dock.name} · ${dock.bikesAvailable} bike${if (dock.bikesAvailable == 1) "" else "s"} available", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { openBikeApp(context, dock.system) }, Modifier.weight(1f)) { Text("Open ${dock.system}") }
            Button(onClick = { session.confirm(UserAction.GOT_BIKE) }, Modifier.weight(1f)) { Text("I've got a bike") }
        }
    }
}

@Composable
private fun BikeDropoffCard(session: TripSession, dock: Dock) {
    InfoCard {
        Text("Dock the bike", style = MaterialTheme.typography.titleMedium)
        Text("${dock.name} · ${dock.spacesAvailable} space${if (dock.spacesAvailable == 1) "" else "s"}", style = MaterialTheme.typography.bodyMedium)
        Button(onClick = { session.confirm(UserAction.DOCKED) }, Modifier.fillMaxWidth()) { Text("Docked") }
    }
}

/** Unlocking stays in the operator's app (spec §4.4). */
private fun openBikeApp(context: Context, system: String) {
    val pm = context.packageManager
    val candidates = if (system == "Santander Cycles") listOf("uk.gov.tfl.cyclehire", "com.tfl.santandercycles", "uk.co.santandercycles") else emptyList()
    val intent = candidates.firstNotNullOfOrNull { pm.getLaunchIntentForPackage(it) }
    if (intent != null) context.startActivity(intent) else openUri(context, "https://tfl.gov.uk/modes/cycling/santander-cycles")
}

@Composable
private fun TripComplete(state: SessionState, onDone: () -> Unit) {
    val plan = state.plan ?: return
    Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.CheckCircle, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
            Text("Trip complete", style = MaterialTheme.typography.headlineSmall)
            Text(
                "${plan.legs.size} stop${if (plan.legs.size == 1) "" else "s"} · ended at ${plan.trip.stops.last().place.label}",
                style = MaterialTheme.typography.bodyMedium,
            )
            val drives = plan.legs.flatMap { it.segments }.filterIsInstance<Segment.Drive>()
            if (drives.isNotEmpty()) {
                Text(
                    "Your drive times have updated the drive-time factor (Settings).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onDone) { Text("Done") }
        }
    }
}
