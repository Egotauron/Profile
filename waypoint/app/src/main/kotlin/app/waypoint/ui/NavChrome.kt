package app.waypoint.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.waypoint.music.NowPlaying
import com.stadiamaps.ferrostar.composeui.theme.DefaultRoadNameViewTheme
import com.stadiamaps.ferrostar.composeui.theme.DefaultTripProgressViewTheme
import com.stadiamaps.ferrostar.composeui.theme.InstructionRowTheme
import com.stadiamaps.ferrostar.composeui.theme.NavigationUITheme
import com.stadiamaps.ferrostar.composeui.theme.RoadNameViewTheme
import com.stadiamaps.ferrostar.composeui.theme.TripProgressViewTheme
import kotlin.time.Duration
import kotlin.time.Instant

// Navigation chrome in the style of Google Maps: a green instruction banner, round map buttons on
// the right, the street you're on just above a bottom sheet with the ETA, and a media panel.

object NavColors {
    val Banner = Color(0xFF1E8E3E)
    val BannerDark = Color(0xFF0D652D)
    val EtaLight = Color(0xFF188038)
    val EtaDark = Color(0xFF81C995)

    /** Follows the app theme (dark map at night), not just the phone setting. */
    @Composable
    fun isDark(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

    @Composable
    fun eta(): Color = if (isDark()) EtaDark else EtaLight
}

/** Ferrostar's instruction banner, painted Google-green with white text. */
object WaypointNavTheme : NavigationUITheme {
    override val instructionRowTheme: InstructionRowTheme
        @Composable get() = object : InstructionRowTheme {
            override val distanceTextStyle: TextStyle
                @Composable get() = MaterialTheme.typography.headlineMedium.merge(color = Color.White, fontWeight = FontWeight.Bold)
            override val instructionTextStyle: TextStyle
                @Composable get() = MaterialTheme.typography.titleLarge.merge(color = Color.White.copy(alpha = 0.92f))
            override val iconTintColor: Color
                @Composable get() = Color.White
            override val backgroundColor: Color
                @Composable get() = if (NavColors.isDark()) NavColors.BannerDark else NavColors.Banner
        }
    override val roadNameViewTheme: RoadNameViewTheme
        @Composable get() = DefaultRoadNameViewTheme
    override val tripProgressViewTheme: TripProgressViewTheme
        @Composable get() = DefaultTripProgressViewTheme
    override val buttonSize: androidx.compose.ui.unit.DpSize
        @Composable get() = androidx.compose.ui.unit.DpSize(56.dp, 56.dp)
}

/** What the bottom sheet shows. */
data class SheetInfo(
    /** Time to the next stop (null while waiting at a stop). */
    val remaining: Duration?,
    val distanceMeters: Double?,
    val arriveAt: Instant?,
    val stopNumber: Int,
    val stopCount: Int,
    val stopName: String?,
    /** When the whole trip ends, shown when there are more stops after this one. */
    val tripEndsAt: Instant?,
)

/**
 * Bottom sheet: ✕ to end the trip, the ETA in large green type with distance and arrival time,
 * the trip menu, and "Stop 2 of 5 · Name" underneath.
 */
@Composable
fun NavSheet(info: SheetInfo, units: app.waypoint.trip.DistanceUnits, onEnd: () -> Unit, menu: @Composable (open: Boolean, close: () -> Unit) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SheetButton(Icons.Filled.Close, "End trip", onEnd)
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    val remaining = info.remaining
                    if (remaining != null) {
                        Text(
                            Format.duration(remaining),
                            style = MaterialTheme.typography.headlineMedium.merge(Numbers),
                            fontWeight = FontWeight.Bold,
                            color = NavColors.eta(),
                        )
                    }
                    Text(
                        listOfNotNull(
                            info.distanceMeters?.let { Format.distance(it, units) },
                            info.arriveAt?.let { Format.clock(it) },
                        ).joinToString(" · ").ifEmpty { info.tripEndsAt?.let { "Trip ends ${Format.clock(it)}" } ?: "" },
                        style = MaterialTheme.typography.bodyLarge.merge(Numbers),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    SheetButton(Icons.Filled.UnfoldMore, "Trip options") { menuOpen = true }
                    menu(menuOpen) { menuOpen = false }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StopBadge(info.stopNumber, 24.dp)
                Text(
                    "Stop ${info.stopNumber} of ${info.stopCount} · ${info.stopName ?: ""}",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (info.tripEndsAt != null && info.stopNumber < info.stopCount) {
                    Text(
                        "ends ${Format.clock(info.tripEndsAt)}",
                        style = MaterialTheme.typography.labelMedium.merge(Numbers),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SheetButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(48.dp)) {
        IconButton(onClick = onClick) { Icon(icon, label) }
    }
}

/** The street you're on, just above the sheet (and so always below your location arrow). */
@Composable
fun RoadNamePill(name: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.inverseSurface, shadowElevation = 3.dp) {
            Text(
                name,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
    }
}

/**
 * The round buttons on the right: mid-screen, lifted just enough to stay clear of the panels at
 * the bottom when those reach higher (the music player, the contacts list). [panelTop] is the top
 * of the bottom panels in window pixels (NaN until known).
 */
@Composable
fun BoxScope.FloatingRightButtons(panelTop: Float, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    var boxTop by remember { mutableFloatStateOf(0f) }
    var boxHeight by remember { mutableIntStateOf(0) }
    var columnHeight by remember { mutableIntStateOf(0) }
    val gap = with(LocalDensity.current) { 12.dp.toPx() }
    Spacer(Modifier.matchParentSize().onGloballyPositioned { boxTop = it.positionInWindow().y; boxHeight = it.size.height })
    val naturalBottom = boxTop + (boxHeight + columnHeight) / 2f
    val lift = if (panelTop.isNaN()) 0f
    else (naturalBottom - (panelTop - gap)).coerceIn(0f, ((boxHeight - columnHeight) / 2f).coerceAtLeast(0f))
    Column(
        modifier.align(Alignment.CenterEnd).onSizeChanged { columnHeight = it.height }.offset { IntOffset(0, -lift.roundToInt()) },
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** A round floating map button (sound, music), as on the right of Google Maps. */
@Composable
fun RoundMapButton(label: String, onClick: () -> Unit, onLongClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    @OptIn(ExperimentalFoundationApi::class)
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 6.dp,
        modifier = Modifier.size(56.dp).clip(CircleShape).combinedClickable(onClickLabel = label, onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
fun SoundButton(muted: Boolean, onToggle: () -> Unit) {
    RoundMapButton(if (muted) "Unmute voice" else "Mute voice", onToggle) {
        Icon(if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, null)
    }
}

/** Shows your player's app icon; tap for the media panel, long-press to pick the default player. */
@Composable
fun MusicButton(appIcon: ImageBitmap?, open: Boolean, onToggle: () -> Unit, onLongPress: () -> Unit) {
    RoundMapButton(if (open) "Hide music controls" else "Show music controls", onToggle, onLongPress) {
        if (appIcon != null) {
            Image(appIcon, null, Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)))
        } else {
            Icon(Icons.Filled.MusicNote, null)
        }
    }
}

/** Google Maps-style media panel: art, track and artist, previous / play-pause / next. */
@Composable
fun MediaPanel(
    now: NowPlaying?,
    hasAccess: Boolean,
    playerName: String?,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onOpenPlayer: () -> Unit,
    onAllowAccess: () -> Unit,
    starting: Boolean = false,
    onBrowse: (() -> Unit)? = null,
) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 6.dp) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val art = now?.art?.asImageBitmap()
                Box(
                    Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(onClick = onOpenPlayer),
                    contentAlignment = Alignment.Center,
                ) {
                    if (art != null) Image(art, "Open ${now.appName}", Modifier.size(52.dp)) else Icon(Icons.Filled.MusicNote, null)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        now?.title ?: if (starting) "Starting…" else if (hasAccess) "Nothing playing" else "Music",
                        style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(now?.artist, now?.appName ?: playerName).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (hasAccess && now != null) {
                    IconButton(onClick = onPrevious) { Icon(Icons.Filled.SkipPrevious, "Previous track") }
                    Surface(shape = CircleShape, color = NavColors.eta(), modifier = Modifier.size(48.dp)) {
                        IconButton(onClick = onPlayPause) {
                            Icon(
                                if (now.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                if (now.isPlaying) "Pause" else "Play",
                                tint = MaterialTheme.colorScheme.surface,
                            )
                        }
                    }
                    IconButton(onClick = onNext) { Icon(Icons.Filled.SkipNext, "Next track") }
                } else {
                    // Nothing playing (or no access to see it): start music here, or pick something.
                    if (onBrowse != null) IconButton(onClick = onBrowse) { Icon(Icons.AutoMirrored.Filled.QueueMusic, "Choose music") }
                    Surface(shape = CircleShape, color = NavColors.eta(), modifier = Modifier.size(48.dp)) {
                        if (starting) {
                            Box(contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(22.dp), color = MaterialTheme.colorScheme.surface, strokeWidth = 2.5.dp)
                            }
                        } else {
                            IconButton(onClick = onPlayPause) { Icon(Icons.Filled.PlayArrow, "Play", tint = MaterialTheme.colorScheme.surface) }
                        }
                    }
                }
            }
            if (!hasAccess) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Allow notification access to control your music here.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onAllowAccess) { Text("Allow", fontSize = 14.sp) }
                }
            }
        }
    }
}
