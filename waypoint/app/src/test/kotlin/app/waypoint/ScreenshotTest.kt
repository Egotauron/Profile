package app.waypoint

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.waypoint.providers.TflClient
import app.waypoint.session.SegmentUi
import app.waypoint.session.SessionState
import app.waypoint.session.TripControls
import app.waypoint.session.UserAction
import app.waypoint.trip.Plan
import app.waypoint.trip.Segment
import app.waypoint.trip.StopWait
import app.waypoint.trip.TripPhase
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import app.waypoint.ui.ArrivalCard
import app.waypoint.ui.FloatingRightButtons
import app.waypoint.ui.BuilderViewModel
import app.waypoint.ui.LocalMapRendering
import app.waypoint.ui.TimelineList
import app.waypoint.ui.TransitRideCard
import app.waypoint.ui.TransitWaitCard
import app.waypoint.ui.TripBuilderScreen
import app.waypoint.ui.TripComplete
import app.waypoint.ui.TripMap
import app.waypoint.ui.WaypointTheme
import java.io.File
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the real screens with the plan from the simulated London day
 * (providers TripSimulationTest) and saves PNGs to app/build/screenshots/. The map itself is a
 * placeholder: MapLibre's native renderer doesn't run on the JVM.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h880dp-xhdpi")
class ScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val plan: Plan = Json { ignoreUnknownKeys = true }
        .decodeFromString(Plan.serializer(), javaClass.getResource("/sim_plan.json")!!.readText())

    private val noControls = object : TripControls {
        override fun startNextLeg() = Unit
        override fun skipNextStop() = Unit
        override fun pauseCountdown() = Unit
        override fun confirm(action: UserAction) = Unit
    }

    private fun shoot(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        rule.setContent {
            CompositionLocalProvider(LocalMapRendering provides false) {
                WaypointTheme(dark = dark) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() } }
            }
        }
        rule.waitForIdle()
        val view = rule.activity.window.decorView.rootView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val file = File("build/screenshots/$name.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("Saved ${file.absolutePath} (${bitmap.width}×${bitmap.height})")
    }

    private fun builderVm(): BuilderViewModel =
        BuilderViewModel(ApplicationProvider.getApplicationContext()).also { it.showForPreview(plan.trip, plan, plan.start) }

    @Test
    fun tripBuilder() = shoot("1-trip-builder") {
        TripBuilderScreen(builderVm(), dark = false, onSearch = {}, onTimeline = {}, onStart = {}, onSaved = {}, onSettings = {})
    }

    /** No stops yet: the full map with "Where to?". */
    @Test
    fun home() {
        val vm = BuilderViewModel(ApplicationProvider.getApplicationContext()).also {
            it.showForPreview(plan.trip.copy(stops = emptyList(), name = null), null, plan.start)
        }
        shoot("0-home") { TripBuilderScreen(vm, dark = false, onSearch = {}, onTimeline = {}, onStart = {}, onSaved = {}, onSettings = {}) }
    }

    @Test
    fun tripBuilderDark() = shoot("2-trip-builder-dark", dark = true) {
        TripBuilderScreen(builderVm(), dark = true, onSearch = {}, onTimeline = {}, onStart = {}, onSaved = {}, onSettings = {})
    }

    @Test
    fun timeline() = shoot("3-timeline") { TimelineList(plan) }

    /** The navigation screen's map with a card on top, as during a trip. */
    @Composable
    private fun OverMap(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize()) {
            TripMap(Modifier.fillMaxSize(), plan.trip, plan, plan.start, dark = false)
            Column(Modifier.align(Alignment.BottomCenter).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
        }
    }

    @Test
    fun arrivalCards() = shoot("4-arrival-auto-and-manual") {
        val now = Clock.System.now()
        val auto = SessionState(plan = plan, phase = TripPhase.AtStop(1, now, StopWait.Countdown(now + 15.minutes)), countdownLeft = 12.minutes + 30.seconds)
        val manual = SessionState(plan = plan, phase = TripPhase.AtStop(0, now, StopWait.ForYou))
        OverMap {
            Text("Auto stop", style = MaterialTheme.typography.labelLarge)
            ArrivalCard(noControls, auto)
            Text("Manual stop", style = MaterialTheme.typography.labelLarge)
            ArrivalCard(noControls, manual)
        }
    }

    @Test
    fun transitCards() = shoot("5-transit-companion") {
        val ride = plan.legs.flatMap { it.segments }.filterIsInstance<Segment.TransitRide>().first()
        val now = Clock.System.now()
        val arrivals = listOf(
            TflClient.Arrival(ride.line, ride.headsign, now + 3.minutes, null),
            TflClient.Arrival(ride.line, ride.headsign, now + 11.minutes, null),
        )
        OverMap {
            TransitWaitCard(noControls, SegmentUi.TransitWait(ride, arrivals))
            TransitRideCard(noControls, SegmentUi.TransitRide(ride, getOffNext = true))
        }
    }

    /** Navigating: green banner, round buttons on the right, media panel, street name and sheet. */
    private fun navigating(name: String, dark: Boolean) = shoot(name, dark = dark) {
        val instruction = uniffi.ferrostar.VisualInstruction(
            primaryContent = uniffi.ferrostar.VisualInstructionContent(
                text = "Sloane Square",
                maneuverType = uniffi.ferrostar.ManeuverType.TURN,
                maneuverModifier = uniffi.ferrostar.ManeuverModifier.LEFT,
                roundaboutExitDegrees = null,
                laneInfo = null,
                exitNumbers = emptyList(),
            ),
            secondaryContent = null,
            subContent = null,
            triggerDistanceBeforeManeuver = 1600.0,
        )
        val now = app.waypoint.music.NowPlaying("com.example.music", "Music", "Black Hole Sun", "Soundgarden", null, isPlaying = true)
        Box(Modifier.fillMaxSize()) {
            TripMap(Modifier.fillMaxSize(), plan.trip, plan, plan.start, dark = dark)
            Column(Modifier.align(Alignment.TopCenter).padding(12.dp)) {
                com.stadiamaps.ferrostar.composeui.views.components.InstructionsView(
                    instructions = instruction,
                    distanceToNextManeuver = 1600.0,
                    theme = app.waypoint.ui.WaypointNavTheme.instructionRowTheme,
                )
            }
            Column(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                app.waypoint.ui.SoundButton(app.waypoint.session.VoiceMode.ON) {}
                app.waypoint.ui.MusicButton(null, open = true, onToggle = {}, onLongPress = {})
            }
            Column(Modifier.align(Alignment.BottomCenter).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Music closed: the street name shows (it makes way when a panel is open).
                if (now.title == null) app.waypoint.ui.MediaPanel(now, hasAccess = true, playerName = "Music", onPrevious = {}, onPlayPause = {}, onNext = {}, onOpenPlayer = {}, onAllowAccess = {})
                app.waypoint.ui.RoadNamePill("King's Road")
                app.waypoint.ui.NavSheet(
                    app.waypoint.ui.SheetInfo(13.minutes, 4667.0, plan.legs[0].arriveAt, 1, plan.legs.size, plan.trip.stops[0].place.label, plan.arriveAt),
                    app.waypoint.trip.DistanceUnits.MILES, onEnd = {},
                ) { _, _ -> }
            }
        }
    }

    @Test
    fun navigatingLight() = navigating("9-navigating", dark = false)

    @Test
    fun navigatingDark() = navigating("10-navigating-dark", dark = true)

    /** A transit leg: the whole leg as steps, on the walk to the stop and on the bus. */
    @Test
    fun transitSteps() = shoot("11-transit-steps") {
        val i = plan.legs.indexOfFirst { it.mode == app.waypoint.trip.TravelMode.TRANSIT }
        val leg = plan.legs[i]
        val stop = plan.trip.stops[i].place.label
        val ride = leg.segments.filterIsInstance<Segment.TransitRide>().first()
        val tube = plan.legs.drop(i + 1).first { l -> l.segments.any { it is Segment.TransitRide } }
        val now = Clock.System.now()
        OverMap {
            Text("Walking to the stop", style = MaterialTheme.typography.labelLarge)
            app.waypoint.ui.LegSteps(leg, 0, stop)
            Text("On the bus", style = MaterialTheme.typography.labelLarge)
            app.waypoint.ui.LegSteps(leg, leg.segments.indexOf(ride), stop)
            TransitRideCard(noControls, SegmentUi.TransitRide(ride, getOffNext = false))
            Text("Tube leg, last walk", style = MaterialTheme.typography.labelLarge)
            app.waypoint.ui.LegSteps(tube, tube.segments.lastIndex, plan.trip.stops[plan.legs.indexOf(tube)].place.label)
            app.waypoint.ui.NavSheet(
                app.waypoint.ui.SheetInfo(18.minutes, null, now + 18.minutes, i + 1, plan.legs.size, stop, plan.arriveAt),
                app.waypoint.trip.DistanceUnits.MILES, onEnd = {},
            ) { _, _ -> }
        }
    }

    private fun sheetInfo() = app.waypoint.ui.SheetInfo(13.minutes, 4667.0, plan.legs[0].arriveAt, 1, plan.legs.size, plan.trip.stops[0].place.label, plan.arriveAt)

    @Composable
    private fun Sheet() = app.waypoint.ui.NavSheet(sheetInfo(), app.waypoint.trip.DistanceUnits.MILES, onEnd = {}) { _, _ -> }

    /** The bottom slot's four states, all the ETA sheet's size: ETA, music, calls, a call. */
    @Test
    fun musicAndCalls() = shoot("12-music-and-calls") {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        fun pi(n: String) = android.app.PendingIntent.getBroadcast(ctx, n.hashCode(), android.content.Intent(n), android.app.PendingIntent.FLAG_IMMUTABLE)
        val people = listOf("Anna Rossi" to true, "Ben Clarke" to true, "Chiara" to false, "Dad" to false, "Ed" to false).mapIndexed { i, (name, star) ->
            app.waypoint.calls.CallContact(i.toLong(), name, star, null, "vnd.android.cursor.item/vnd.com.whatsapp.voip.call", "com.whatsapp")
        }
        val ongoing = app.waypoint.calls.LiveCall("com.whatsapp", "WhatsApp", "Anna Rossi", incoming = false,
            since = System.currentTimeMillis() - 154_000, hangUp = pi("h"))
        val nowPlaying = app.waypoint.music.NowPlaying("com.apple.android.music", "Apple Music", "Black Hole Sun", "Soundgarden", null, isPlaying = true)
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("ETA", style = MaterialTheme.typography.labelLarge)
            app.waypoint.ui.BottomSlot(sheet = { Sheet() }, widget = null)
            Text("Music", style = MaterialTheme.typography.labelLarge)
            app.waypoint.ui.BottomSlot(sheet = { Sheet() }) { m ->
                app.waypoint.ui.MediaPanel(nowPlaying, hasAccess = true, playerName = "Apple Music", onPrevious = {}, onPlayPause = {}, onNext = {},
                    onOpenPlayer = {}, onAllowAccess = {}, modifier = m)
            }
            Text("Calls", style = MaterialTheme.typography.labelLarge)
            app.waypoint.ui.BottomSlot(sheet = { Sheet() }) { m ->
                app.waypoint.ui.CallPanel(people, hasContactsAccess = true, whatsAppInstalled = true, appIcon = null, photo = { null },
                    onAllowContacts = {}, onCall = {}, modifier = m)
            }
            Text("On a call", style = MaterialTheme.typography.labelLarge)
            app.waypoint.ui.BottomSlot(sheet = { Sheet() }) { m ->
                app.waypoint.ui.LiveCallPanel(ongoing, photo = null, hasNotificationAccess = true, onAnswer = {}, onDecline = {}, onHangUp = {},
                    onOpen = {}, onAllowAccess = {}, modifier = m)
            }
        }
    }

    /** Calls open, as laid out on the map: the street name and buttons stay where they were. */
    @Test
    fun callsOpenLayout() = shoot("13-calls-open") {
        val people = listOf("Anna Rossi", "Ben Clarke", "Chiara", "Dad", "Ed").mapIndexed { i, name ->
            app.waypoint.calls.CallContact(i.toLong(), name, i < 2, null, "vnd.android.cursor.item/vnd.com.whatsapp.voip.call", "com.whatsapp")
        }
        var panelTop by remember { mutableFloatStateOf(Float.NaN) }
        Box(Modifier.fillMaxSize()) {
            TripMap(Modifier.fillMaxSize(), plan.trip, plan, plan.start, dark = false)
            FloatingRightButtons(panelTop, Modifier.padding(end = 12.dp)) {
                app.waypoint.ui.SoundButton(app.waypoint.session.VoiceMode.ON) {}
                app.waypoint.ui.MusicButton(null, open = false, onToggle = {}, onLongPress = {})
                app.waypoint.ui.CallButton(live = false, open = true) {}
            }
            Column(Modifier.align(Alignment.BottomCenter).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.onGloballyPositioned { panelTop = it.positionInWindow().y }) { app.waypoint.ui.RoadNamePill("King's Road") }
                app.waypoint.ui.BottomSlot(sheet = { Sheet() }) { m ->
                    app.waypoint.ui.CallPanel(people, hasContactsAccess = true, whatsAppInstalled = true, appIcon = null, photo = { null },
                        onAllowContacts = {}, onCall = {}, modifier = m)
                }
            }
        }
    }

    /** A transit leg's three routes in the builder, the chosen one highlighted. */
    @Test
    fun transitOptions() = shoot("14-transit-options") {
        val leg = plan.legs.first { l -> l.segments.any { it is Segment.TransitRide } }
        val rides = leg.segments.filterIsInstance<Segment.TransitRide>()
        val walks = leg.segments.filterIsInstance<Segment.Walk>()
        val mine = app.waypoint.trip.LegOption(leg.mode, leg.departAt, leg.arriveAt, leg.segments)
        val one = rides.first()
        val bus14 = mine.copy(
            arriveAt = leg.arriveAt + 4.minutes,
            segments = listOf(walks.first(), one.copy(line = "14", arriveAt = one.arriveAt + 14.minutes), walks.last()),
        )
        val tube = mine.copy(
            arriveAt = leg.arriveAt - 3.minutes,
            segments = listOf(walks.first(), one.copy(line = "Piccadilly", vehicle = app.waypoint.trip.TransitVehicle.SUBWAY), walks.last()),
        )
        val withOptions = leg.copy(transitOptions = listOf(tube, mine, bus14))
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            androidx.compose.material3.Card {
                Column(Modifier.padding(12.dp)) { app.waypoint.ui.TransitOptions(withOptions) {} }
            }
        }
    }

    @Test
    fun manualStayPrompt() = shoot("8-manual-stay-prompt") {
        // The dialog's own window isn't captured, so its content is drawn on a card.
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            androidx.compose.material3.Card {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Manual at Royal Albert Hall", style = MaterialTheme.typography.headlineSmall)
                    app.waypoint.ui.StayChoices(15.minutes) {}
                }
            }
        }
    }

    @Test
    fun tripComplete() = shoot("7-trip-complete") {
        TripComplete(SessionState(plan = plan, phase = TripPhase.Finished(Clock.System.now())), onDone = {})
    }
}
