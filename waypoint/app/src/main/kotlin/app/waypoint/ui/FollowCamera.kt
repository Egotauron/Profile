package app.waypoint.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.waypoint.trip.TravelMode
import com.stadiamaps.ferrostar.maplibreui.runtime.NavigationCameraMode
import com.stadiamaps.ferrostar.maplibreui.runtime.NavigationMapState
import kotlin.time.Duration.Companion.milliseconds
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.spatialk.geojson.Position
import uniffi.ferrostar.UserLocation

/**
 * Keeps the navigation map on you, in place of Ferrostar's follow mode.
 *
 * Ferrostar's follow mode re-sets the camera on every redraw of the screen, whatever caused it
 * (the clock, the music, a panel), snapping the tilted camera each time; that's what made the map
 * glitch while it followed you, and not in free view. This moves the camera only when your
 * position or direction changes, as one smooth MapLibre animation, and leaves Ferrostar's own
 * mode on FREE so it never touches the camera. Your position is shown as it is: nothing is held.
 *
 * Panning the map stops following ([onStopFollowing]); the recentre button sets [following] again.
 * [aboveY] is how far down the screen the bottom panel starts: you're kept a little above it.
 */
@Composable
fun FollowCamera(
    state: NavigationMapState,
    following: Boolean,
    onStopFollowing: () -> Unit,
    here: UserLocation?,
    mode: TravelMode,
    tilted: Boolean,
    aboveY: Dp?,
) {
    val stopFollowing by rememberUpdatedState(onStopFollowing)
    LaunchedEffect(state) {
        snapshotFlow { state.cameraMode }.collect { if (it != NavigationCameraMode.FREE) state.cameraMode = NavigationCameraMode.FREE }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.cameraState.moveReason }.collect { if (it == CameraMoveReason.GESTURE) stopFollowing() }
    }

    // You sit a little above the bottom panel: MapLibre centres the target in the padded view.
    val screen = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val youAt = (aboveY ?: (screen * 0.75f)) - 130.dp
    val padTop = (youAt * 2 - screen).coerceAtLeast(0.dp)

    // Starting (or restarting) to follow sets the zoom and tilt; after that your own zoom stays.
    var fresh by remember { mutableStateOf(true) }
    LaunchedEffect(following) { if (following) fresh = true }

    val target = here?.coordinates
    val bearing = here?.courseOverGround?.degrees?.toDouble()
    LaunchedEffect(following, target, bearing, tilted, padTop) {
        if (!following || target == null) return@LaunchedEffect
        val camera = state.cameraState
        val start = fresh
        camera.animateTo(
            finalPosition = CameraPosition(
                target = Position(target.lng, target.lat),
                zoom = if (start) followZoom(mode) else camera.position.zoom,
                tilt = if (tilted) followTilt(mode) else 0.0,
                bearing = bearing ?: camera.position.bearing,
                padding = PaddingValues(top = padTop),
            ),
            duration = if (start) 800.milliseconds else 500.milliseconds,
        )
        fresh = false
    }
}

private fun followZoom(mode: TravelMode): Double = when (mode) {
    TravelMode.WALK -> 18.0
    TravelMode.CYCLE -> 17.0
    else -> 16.0
}

private fun followTilt(mode: TravelMode): Double = if (mode == TravelMode.WALK) 20.0 else 45.0
