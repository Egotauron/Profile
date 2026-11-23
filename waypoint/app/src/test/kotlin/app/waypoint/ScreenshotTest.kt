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
import app.waypoint.ui.ArrivalCard
import app.waypoint.ui.BikeDropoffCard
import app.waypoint.ui.BikePickupCard
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

    @Test
    fun bikeShareCards() = shoot("6-bike-share") {
        val segs = plan.legs.flatMap { it.segments }
        OverMap {
            BikePickupCard(noControls, segs.filterIsInstance<Segment.BikePickup>().first().dock)
            BikeDropoffCard(noControls, segs.filterIsInstance<Segment.BikeDropoff>().first().dock)
        }
    }

    @Test
    fun tripComplete() = shoot("7-trip-complete") {
        TripComplete(SessionState(plan = plan, phase = TripPhase.Finished(Clock.System.now())), onDone = {})
    }
}
