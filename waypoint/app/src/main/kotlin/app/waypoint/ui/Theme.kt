package app.waypoint.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Palette: the deep green of road signs and OS maps, a signal green for "go", and the amber of
// the stop markers. Mode colours are kept apart from the brand colours.
object Palette {
    val Pine = Color(0xFF0F3D33)
    val Signal = Color(0xFF0B7A5C)
    val SignalLight = Color(0xFF5FD3AE)
    val Amber = Color(0xFFF2B53A)
    val Ground = Color(0xFFF2F4F1)
    val Ink = Color(0xFF14211C)
    val Night = Color(0xFF0D1412)
    val NightSurface = Color(0xFF17221E)
    val Warn = Color(0xFFB3261E)

    val Drive = Color(0xFF2B5FD9)
    val Walk = Color(0xFF52615C)
    val Cycle = Color(0xFF0B8F5A)
    val BikeShare = Color(0xFFD9481F)
    val Transit = Color(0xFF6A3FB5)
    val Bus = Color(0xFFD12A2A)
    val Tube = Color(0xFF1C3F94)
    val Train = Color(0xFF4A5560)
    val Tram = Color(0xFF5EA637)
}

private val Light = lightColorScheme(
    primary = Palette.Signal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEBDD),
    onPrimaryContainer = Palette.Pine,
    secondary = Palette.Pine,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDE6E1),
    onSecondaryContainer = Palette.Ink,
    tertiary = Color(0xFF8A5A00),
    tertiaryContainer = Color(0xFFFFE3A8),
    onTertiaryContainer = Color(0xFF3B2600),
    background = Palette.Ground,
    onBackground = Palette.Ink,
    surface = Color.White,
    onSurface = Palette.Ink,
    surfaceVariant = Color(0xFFE5EAE7),
    onSurfaceVariant = Color(0xFF46524D),
    surfaceContainer = Color(0xFFF7F9F7),
    surfaceContainerHigh = Color(0xFFEEF2EF),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF9FBF9),
    surfaceContainerHighest = Color(0xFFE6ECE8),
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFDDE4E0),
    outline = Color(0xFF76847E),
    outlineVariant = Color(0xFFC8D1CC),
    error = Palette.Warn,
)

private val Dark = darkColorScheme(
    primary = Palette.SignalLight,
    onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF0B513F),
    onPrimaryContainer = Color(0xFFCDEBDD),
    secondary = Color(0xFFB6CCC3),
    onSecondary = Color(0xFF21352E),
    secondaryContainer = Color(0xFF2D3F38),
    onSecondaryContainer = Color(0xFFDDE6E1),
    tertiary = Palette.Amber,
    tertiaryContainer = Color(0xFF5C4300),
    onTertiaryContainer = Color(0xFFFFE3A8),
    background = Palette.Night,
    onBackground = Color(0xFFE2E9E5),
    surface = Palette.NightSurface,
    onSurface = Color(0xFFE2E9E5),
    surfaceVariant = Color(0xFF26322D),
    onSurfaceVariant = Color(0xFFB9C6C0),
    surfaceContainer = Color(0xFF1B2723),
    surfaceContainerHigh = Color(0xFF223029),
    surfaceContainerLowest = Color(0xFF0A110F),
    surfaceContainerLow = Color(0xFF151F1B),
    surfaceContainerHighest = Color(0xFF2A3833),
    surfaceBright = Color(0xFF2E3B36),
    surfaceDim = Palette.Night,
    outline = Color(0xFF84918B),
    outlineVariant = Color(0xFF3A4741),
    error = Color(0xFFF2B8B5),
)

private val Type = Typography().let { t ->
    t.copy(
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = t.labelSmall.copy(letterSpacing = 0.6.sp, fontWeight = FontWeight.Medium),
    )
}

/** Times and durations line up in columns. */
val Numbers = TextStyle(fontFeatureSettings = "tnum")

@Composable
fun WaypointTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = Type, content = content)
}
