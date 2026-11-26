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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.waypoint.graph
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.layout.positionInWindow
import androidx.activity.result.contract.ActivityResultContracts
import app.waypoint.music.MusicController
import app.waypoint.session.SegmentUi
import app.waypoint.session.SessionState
import app.waypoint.session.TripControls
import app.waypoint.session.TripSession
import app.waypoint.session.UserAction
import app.waypoint.trip.OpenIn
import app.waypoint.trip.Segment
import app.waypoint.trip.StopWait
import app.waypoint.trip.TripPhase
import androidx.compose.material.icons.filled.Navigation
import com.stadiamaps.ferrostar.maplibreui.runtime.rememberNavigationMapState
import com.stadiamaps.ferrostar.maplibreui.runtime.NavigationCameraMode
import com.stadiamaps.ferrostar.composeui.views.components.InstructionsView
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Clock
import org.maplibre.compose.style.BaseStyle
import androidx.compose.foundation.layout.PaddingValues
import org.maplibre.compose.map.OrnamentOptions
import org.maplibre.compose.map.MapOptions
import com.stadiamaps.ferrostar.maplibreui.routeline.RouteOverlayBuilder
import com.stadiamaps.ferrostar.maplibreui.routeline.BorderedPolyline
import com.stadiamaps.ferrostar.maplibreui.NavigationMapView
import kotlinx.coroutines.delay

@Composable
fun NavigationScreen(session: TripSession, music: MusicController, dark: Boolean, onTimeline: () -> Unit, onExit: () -> Unit) {
    val state by session.state.collectAsState()
    var confirmEnd by remember { mutableStateOf(false) }
    var musicOpen by remember { mutableStateOf(false) }
    val plan = state.plan

    if (plan == null) {
        LaunchedEffect(Unit) { onExit() }
        return
    }
    if (state.phase is TripPhase.Finished) {
        TripComplete(state, onDone = { session.endTrip(); onExit() })
        return
    }

    val context = LocalContext.current
    val units = context.graph.settings.current.units
    val voiceMode by session.voice.mode.collectAsState()

    // The screen stays on while you navigate.
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    val now by music.nowPlaying.collectAsState()
    val access by music.access.collectAsState()
    var pickPlayer by remember { mutableStateOf(false) }
    var explainAccess by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { music.refreshAccess() }
    val playerPkg = now?.packageName ?: music.currentPlayerPackage()
    val appIcon = remember(playerPkg) { music.appIcon(playerPkg)?.asImageBitmap() }
    val starting by music.starting.collectAsState()
    var browseMusic by remember { mutableStateOf(false) }

    val calls = context.graph.calls
    val liveCall by calls.liveCall.collectAsState()
    var callsOpen by remember { mutableStateOf(false) }
    var contactsAccess by remember { mutableStateOf(calls.hasContactsAccess()) }
    var contacts by remember { mutableStateOf<List<app.waypoint.calls.CallContact>?>(null) }
    val whatsApp = remember { calls.whatsAppInstalled() }
    val askContacts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { contactsAccess = it }
    // WhatsApp only starts calls for apps with the Phone permission: asked for on the first call,
    // which then goes ahead.
    val activity = androidx.activity.compose.LocalActivity.current
    var callWaiting by remember { mutableStateOf<app.waypoint.calls.CallContact?>(null) }
    val askPhone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val who = callWaiting
        callWaiting = null
        if (granted) {
            who?.let(calls::call)
        } else if (activity != null && !androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(activity, android.Manifest.permission.CALL_PHONE)) {
            // Android won't ask again: the switch is in Waypoint's settings.
            android.widget.Toast.makeText(context, "To call from the map, allow Phone in Waypoint's permissions", android.widget.Toast.LENGTH_LONG).show()
            runCatching {
                context.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        } else {
            android.widget.Toast.makeText(context, "WhatsApp only starts calls for apps allowed to make phone calls", android.widget.Toast.LENGTH_LONG).show()
        }
    }
    val placeCall: (app.waypoint.calls.CallContact) -> Unit = { who ->
        if (calls.hasCallAccess()) {
            calls.call(who)
        } else {
            callWaiting = who
            askPhone.launch(android.Manifest.permission.CALL_PHONE)
        }
    }
    val whatsAppIcon = remember { (music.appIcon("com.whatsapp") ?: music.appIcon("com.whatsapp.w4b"))?.asImageBitmap() }
    val caller = liveCall?.caller
    val callerPhoto by produceState<android.graphics.Bitmap?>(null, caller, contactsAccess) { value = caller?.let { calls.photoFor(it) } }
    LaunchedEffect(callsOpen, contactsAccess) { if (callsOpen && contactsAccess) contacts = calls.contacts() }
    // Diagnostic build: the report of the last WhatsApp call attempt, shown when one fails.
    val callReport by app.waypoint.calls.CallReports.last.collectAsState()
    var showCallReport by remember { mutableStateOf(false) }
    LaunchedEffect(callReport) { if (callReport?.failed == true) showCallReport = true }

    // Call audio output: the button steps through the outputs there are (Bluetooth, phone,
    // speaker); WhatsApp's own output button is pressed for it (see OutputSwitch).
    val output by calls.output.collectAsState(initial = null)
    var outputSetup by remember { mutableStateOf(false) }
    var outputFailure by remember { mutableStateOf<app.waypoint.calls.OutputSwitch.Result?>(null) }
    val switched by app.waypoint.calls.OutputSwitch.result.collectAsState()
    LaunchedEffect(switched) {
        val result = switched ?: return@LaunchedEffect
        if (result.reached) {
            android.widget.Toast.makeText(context, "Call audio: ${result.target.label}", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            outputFailure = result
        }
    }
    fun copyReport(text: String) {
        context.getSystemService(android.content.ClipboardManager::class.java)
            .setPrimaryClip(android.content.ClipData.newPlainText("Waypoint report", text))
        android.widget.Toast.makeText(context, "Report copied", android.widget.Toast.LENGTH_SHORT).show()
    }

    // Top of the bottom panels, so the buttons on the right can stay clear of them.
    var panelTop by remember { mutableFloatStateOf(Float.NaN) }

    /** Sound, music and calls, on the right like Google Maps. */
    // Following you on the map (see FollowCamera); panning stops it, the recentre button restarts it.
    var following by remember { mutableStateOf(true) }

    @Composable
    fun BoxScope.RightButtons(modifier: Modifier) {
        FloatingRightButtons(panelTop, modifier) {
            if (!following && state.segment is SegmentUi.Guiding) {
                RoundMapButton("Follow my position", onClick = { following = true }) { Icon(Icons.Filled.Navigation, null) }
            }
            SoundButton(voiceMode) {
                val now = session.cycleVoice()
                android.widget.Toast.makeText(
                    context,
                    when (now) {
                        app.waypoint.session.VoiceMode.ON -> "Voice on"
                        app.waypoint.session.VoiceMode.QUIET -> "Voice: road changes only"
                        app.waypoint.session.VoiceMode.OFF -> "Voice off"
                    },
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
            MusicButton(appIcon, musicOpen, onToggle = { musicOpen = !musicOpen; if (musicOpen) callsOpen = false }, onLongPress = { pickPlayer = true })
            CallButton(live = liveCall != null, open = callsOpen) { callsOpen = !callsOpen; if (callsOpen) musicOpen = false }
        }
    }

    /**
     * What floats above the sheet: a message, the transit step strip, a card, the street name.
     * During guidance it floats over the map rather than being part of Ferrostar's bottom view, so
     * it appearing or changing height never moves the map.
     */
    @Composable
    fun FloatingStack(modifier: Modifier, roadName: String?, card: (@Composable () -> Unit)?) {
        Column(
            modifier.fillMaxWidth().onGloballyPositioned { panelTop = it.positionInWindow().y },
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            state.message?.let { msg ->
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.inverseSurface, shadowElevation = 4.dp) {
                    Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(msg, color = MaterialTheme.colorScheme.inverseOnSurface, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = session::dismissMessage) { Text("OK", color = MaterialTheme.colorScheme.inversePrimary) }
                    }
                }
            }
            val leg = state.legIndex?.let { plan.legs.getOrNull(it) }
            if (state.atStop == null && leg != null && leg.hasSteps()) LegSteps(leg, state.segmentIndex, state.stopName)
            card?.invoke()
            roadName?.takeIf { it.isNotBlank() }?.let { RoadNamePill(it) }
        }
    }

    /**
     * The bottom of the screen: the ETA sheet, or in its place the live call, the WhatsApp
     * contacts or the music player (the buttons on the right switch them). Same size whatever
     * shows, so nothing else moves.
     */
    @Composable
    fun Sheet(info: SheetInfo) {
        val call = liveCall
        val widget: (@Composable (Modifier) -> Unit)? = when {
            call != null -> { m ->
                LiveCallPanel(
                    call,
                    photo = callerPhoto,
                    hasNotificationAccess = access,
                    onAnswer = { calls.answer(call) },
                    onDecline = { calls.decline(call) },
                    onHangUp = { calls.hangUp(call) },
                    onOpen = { calls.open(call) },
                    onAllowAccess = { explainAccess = true },
                    modifier = m,
                    output = output,
                    onOutput = if (!call.incoming && call.packageName in app.waypoint.calls.CallController.APPS.keys) {
                        {
                            val target = app.waypoint.calls.WhatsAppOutput.cycle(calls.outputs.current(), calls.outputs.available())
                            when {
                                !calls.callScreenReaderOn() -> outputSetup = true
                                app.waypoint.calls.OutputSwitch.request != null -> Unit // already switching
                                target == null -> android.widget.Toast.makeText(context, "No other audio output to switch to", android.widget.Toast.LENGTH_SHORT).show()
                                else -> calls.switchOutput(call, target)
                            }
                        }
                    } else null,
                )
            }
            callsOpen -> { m ->
                CallPanel(
                    contacts = contacts,
                    hasContactsAccess = contactsAccess,
                    whatsAppInstalled = whatsApp,
                    appIcon = whatsAppIcon,
                    photo = calls::photo,
                    onAllowContacts = { askContacts.launch(android.Manifest.permission.READ_CONTACTS) },
                    onCall = { callsOpen = false; placeCall(it) },
                    modifier = m,
                    onLastAttempt = if (callReport != null) ({ showCallReport = true }) else null,
                )
            }
            musicOpen -> { m ->
                MediaPanel(
                    now = now,
                    hasAccess = access,
                    playerName = music.playerName(playerPkg),
                    onPrevious = music::previous,
                    onPlayPause = music::playPause,
                    onNext = music::next,
                    onOpenPlayer = { music.launchPlayer(playerPkg) },
                    onAllowAccess = { explainAccess = true },
                    starting = starting,
                    onBrowse = { if (playerPkg != null) browseMusic = true else pickPlayer = true },
                    modifier = m,
                )
            }
            else -> null
        }
        BottomSlot(
            sheet = { NavSheet(info, units, onEnd = { confirmEnd = true }) { open, close -> TripMenu(open, close, state, onTimeline) } },
            widget = widget,
        )
    }

    val gpsLine by session.gpsDetails.collectAsState()

    // During guidance: where the bottom slot starts (window pixels), to keep you above it.
    var sheetTop by remember { mutableFloatStateOf(Float.NaN) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val windowHeight = with(density) { androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height.toDp() }

    val seg = state.segment
    if (seg is SegmentUi.Guiding && state.atStop == null) {
        val mapState = rememberNavigationMapState(initialCameraMode = NavigationCameraMode.FREE)
        val ui by session.mapViewModel.navigationUiState.collectAsState()
        val settings by context.graph.settings.settings.collectAsState(context.graph.settings.current)
        val sheetTopDp = if (sheetTop.isNaN()) null else with(density) { sheetTop.toDp() }
        LaunchedEffect(Unit) { following = true }
        FollowCamera(
            state = mapState,
            following = following,
            onStopFollowing = { following = false },
            here = ui.location,
            mode = seg.mode,
            tilted = settings.tiltedMap,
            aboveY = sheetTopDp,
        )
        val routeColour = LocalRouteColour.current
        val routeLine = remember(routeColour) {
            RouteOverlayBuilder(navigationPath = { u -> u.routeGeometry?.let { BorderedPolyline(points = it, color = routeColour) } })
        }
        // Ferrostar's map only. The banner, buttons and bottom slot are Waypoint's: Ferrostar's
        // own navigation screen adds a centre button that fights the follow camera.
        Box(Modifier.fillMaxSize()) {
            NavigationMapView(
                baseStyle = BaseStyle.Uri(if (dark) MapStyles.NIGHT else LocalDayMapStyle.current),
                navigationMapState = mapState,
                uiState = ui,
                mapOptions = MapOptions(
                    ornamentOptions = OrnamentOptions(
                        isLogoEnabled = false,
                        isAttributionEnabled = true,
                        isCompassEnabled = false,
                        isScaleBarEnabled = false,
                        // "© OpenStreetMap contributors" stays visible above the bottom slot.
                        padding = PaddingValues(bottom = sheetTopDp?.let { windowHeight - it } ?: 160.dp),
                    ),
                ),
                routeOverlayBuilder = routeLine,
            ) { _ ->
                // Ferrostar draws the segment being guided; this adds the rest of the trip.
                TripLayers(plan.trip, plan, null, dark, fromLeg = state.legIndex ?: 0, afterSegment = state.segmentIndex)
            }
            ui.visualInstruction?.let { instruction ->
                InstructionsView(
                    instructions = instruction,
                    distanceToNextManeuver = ui.progress?.distanceToNextManeuver,
                    modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                    theme = WaypointNavTheme.instructionRowTheme,
                    remainingSteps = ui.remainingSteps,
                )
            }
            gpsLine?.let { line ->
                Surface(
                    Modifier.align(Alignment.CenterStart).padding(start = 8.dp).widthIn(max = 220.dp),
                    shape = RoundedCornerShape(8.dp), color = Color(0xCC000000),
                ) { Text(line, color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(6.dp)) }
            }
            RightButtons(Modifier.statusBarsPadding().padding(end = 12.dp))
            Column(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FloatingStack(Modifier, roadName = ui.currentStepRoadName, card = null)
                Box(Modifier.onGloballyPositioned { sheetTop = it.positionInWindow().y }) {
                    Sheet(sheetInfo(state, ui.progress?.durationRemaining?.seconds, ui.progress?.distanceRemaining))
                }
            }
        }
    } else {
        Box(Modifier.fillMaxSize()) {
            val focus = state.legIndex ?: state.atStop?.let { it.stopIndex + 1 }?.takeIf { it < plan.legs.size }
            TripMap(
                Modifier.fillMaxSize(), plan.trip, plan, plan.start, dark, user = state.userLocation, focusLeg = focus, bottomPadding = 260,
                fromLeg = focus ?: 0, afterSegment = if (state.legIndex != null) state.segmentIndex - 1 else -1,
            )
            RightButtons(Modifier.statusBarsPadding().padding(end = 12.dp))
            Column(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
            FloatingStack(
                Modifier,
                roadName = null,
                card = when {
                    state.atStop != null -> ({ ArrivalCard(session, state) })
                    seg is SegmentUi.TransitWait -> ({ TransitWaitCard(session, seg) })
                    seg is SegmentUi.TransitRide -> ({ TransitRideCard(session, seg) })
                    seg is SegmentUi.Preparing -> ({
                        InfoCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.size(12.dp))
                                Text(seg.text)
                            }
                        }
                    })
                    else -> null
                },
            )
            Sheet(sheetInfo(state, null, null))
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
    if (showCallReport) callReport?.let { report ->
        CallReportDialog(
            report,
            onCopy = { copyReport(report.text) },
            onOpenChat = report.chat?.let { chat ->
                {
                    showCallReport = false
                    runCatching { context.startActivity(android.content.Intent(chat).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            },
            onDismiss = { showCallReport = false },
        )
    }
    outputFailure?.let { failure ->
        val text = failure.report ?: failure.why
        CallReportDialog(
            app.waypoint.calls.CallReport(failed = true, text = text, chat = null, title = "Couldn't switch to ${failure.target.label}"),
            onCopy = { copyReport(text) },
            onOpenChat = null,
            onDismiss = { outputFailure = null },
        )
    }
    if (outputSetup) {
        fun openSettings(intent: android.content.Intent) {
            runCatching { context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        OutputSetupDialog(
            onAccessibility = { outputSetup = false; openSettings(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            onAppInfo = {
                openSettings(
                    android.content.Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null),
                    ),
                )
            },
            onDismiss = { outputSetup = false },
        )
    }
    if (pickPlayer) PlayerPicker(music, onDismiss = { pickPlayer = false })
    if (browseMusic && playerPkg != null) {
        PlaylistPicker(
            music.library, playerPkg, music.playerName(playerPkg) ?: "Music",
            onDismiss = { browseMusic = false },
            onPick = { browseMusic = false; music.play(it) },
        )
    }
    if (explainAccess) MusicAccessDialog(onDismiss = { explainAccess = false })
}

/**
 * The sheet's numbers: time and distance to the next stop, its arrival time, and the trip's end.
 * [segmentLeft] is Ferrostar's time left on the step being guided; the steps after it (the bus,
 * the walk from the stop) are added so the sheet shows when you reach the stop, not the bus stop.
 */
private fun sheetInfo(state: SessionState, segmentLeft: kotlin.time.Duration?, distanceMeters: Double?): SheetInfo {
    val plan = state.plan
    val nowT = Clock.System.now()
    val leg = state.legIndex?.let { plan?.legs?.getOrNull(it) }
    val arriveAt = when {
        state.atStop != null || leg == null -> null
        else -> {
            val here = leg.segments.getOrNull(state.segmentIndex)
            val currentEnds = when {
                segmentLeft != null -> nowT + segmentLeft
                here is Segment.TransitRide -> maxOf(nowT, here.arriveAt)
                else -> null
            }
            currentEnds?.let { legArrival(leg, state.segmentIndex, it) } ?: maxOf(nowT, leg.arriveAt)
        }
    }
    // Ferrostar's distance is for this step only, so it's shown only when nothing else follows.
    val lastStep = leg != null && leg.segments.drop(state.segmentIndex + 1).all { it is Segment.Park }
    return SheetInfo(
        remaining = arriveAt?.let { it - nowT },
        distanceMeters = if (arriveAt != null && lastStep) distanceMeters else null,
        arriveAt = arriveAt,
        stopNumber = state.stopNumber,
        stopCount = state.stopCount,
        stopName = state.stopName,
        tripEndsAt = plan?.arriveAt,
    )
}

/** The sheet's menu: timeline, open the remaining stops in another app, end the trip. */
@Composable
private fun TripMenu(open: Boolean, close: () -> Unit, state: SessionState, onTimeline: () -> Unit) {
    val context = LocalContext.current
    DropdownMenu(open, close) {
        DropdownMenuItem({ Text("Timeline") }, { close(); onTimeline() })
        remainingTargets(state).takeIf { it.isNotEmpty() }?.let { remaining ->
            val mode = state.plan?.legs?.getOrNull(state.stopNumber - 1)?.mode ?: app.waypoint.trip.TravelMode.DRIVE
            DropdownMenuItem({ Text("Open in Google Maps") }, { close(); openUri(context, OpenIn.googleMaps(state.userLocation, remaining, mode)) })
            DropdownMenuItem({ Text("Open in OsmAnd") }, { close(); openUri(context, OpenIn.osmAndAllStops(remaining, mode)) })
            DropdownMenuItem({ Text("Open in Organic Maps (next stop)") }, { close(); openUri(context, OpenIn.organicMaps(state.userLocation, remaining.first(), mode)) })
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

/** Long-press on the music button: choose the player it opens. */
@Composable
private fun PlayerPicker(music: MusicController, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val players = remember { music.installedPlayers() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Default music player") },
        text = {
            Column {
                players.forEach { p ->
                    TextButton(onClick = {
                        context.graph.settings.update { it.copy(defaultMusicPackage = p.packageName) }
                        onDismiss()
                    }) { Text(p.name) }
                }
                if (players.isEmpty()) Text("No music apps found.")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun MusicAccessDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Music and calls on the map") },
        text = {
            Text(
                "To show what's playing with previous, play/pause and next, and to show calls with their answer and " +
                    "hang-up buttons, Waypoint needs notification access. It only reads your player and call " +
                    "notifications; nothing is stored or leaves the phone.\n\n" +
                    "If the switch is greyed out: App info → ⋮ → Allow restricted settings, then try again.",
            )
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(); context.startActivity(MusicController.notificationAccessIntent()) }) { Text("Open settings") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}

@Composable
internal fun InfoCard(content: @Composable () -> Unit) {
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
internal fun ArrivalCard(session: TripControls, state: SessionState) {
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
internal fun TransitWaitCard(session: TripControls, seg: SegmentUi.TransitWait) {
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
        Text(
            if (app.waypoint.trip.London.inGreaterLondon(ride.board.latLng)) "Powered by TfL Open Data" else "Timetables from Transitous",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun TransitRideCard(session: TripControls, seg: SegmentUi.TransitRide) {
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
        Text(
            if (app.waypoint.trip.London.inGreaterLondon(ride.board.latLng)) "Powered by TfL Open Data" else "Timetables from Transitous",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun TripComplete(state: SessionState, onDone: () -> Unit) {
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
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            Button(onClick = onDone) { Text("Done") }
        }
    }
}
