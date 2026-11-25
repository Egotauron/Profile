package app.waypoint.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.waypoint.data.AccentPalette
import app.waypoint.data.AppSettings
import app.waypoint.data.DayMap
import app.waypoint.data.RouteColour
import app.waypoint.data.ThemeMode

/**
 * Settings, Appearance: light or dark, the colour palette, the route colour, the day map and the
 * 3D tilt, with a preview of the navigation banner, ETA and route line in the current choices.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceSettings(s: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AppearancePreview()

        Label("Theme")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { m ->
                FilterChip(selected = s.themeMode == m, onClick = { update { it.copy(themeMode = m) } }, label = { Text(m.label) })
            }
        }

        Label("Colours")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AccentPalette.entries.forEach { p ->
                val a = p.accent(NavColors.isDark())
                Swatch(p.label, a.banner, selected = s.palette == p) { update { it.copy(palette = p) } }
            }
        }

        Label("Route line")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RouteColour.entries.forEach { r ->
                Swatch(r.label, Color(r.argb), selected = s.routeColour == r) { update { it.copy(routeColour = r) } }
            }
        }

        Label("Daytime map")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DayMap.entries.forEach { d ->
                FilterChip(selected = s.dayMap == d, onClick = { update { it.copy(dayMap = d) } }, label = { Text(d.label) })
            }
        }
    }
}

@Composable
private fun Label(text: String) = Text(text, style = MaterialTheme.typography.titleSmall)

/** A round colour sample with its name; ticked when chosen. */
@Composable
private fun Swatch(name: String, colour: Color, selected: Boolean, onPick: () -> Unit) {
    Column(
        Modifier.width(64.dp).clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = name, onClick = onPick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(40.dp).background(colour, CircleShape)
                .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) { if (selected) Icon(Icons.Filled.Check, "Chosen", tint = Color.White) }
        Spacer(Modifier.height(4.dp))
        Text(name, style = MaterialTheme.typography.labelMedium)
    }
}

/** The banner, ETA and route line as they'll look while navigating. */
@Composable
fun AppearancePreview() {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth().background(NavColors.banner(), RoundedCornerShape(14.dp)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.TurnLeft, null, Modifier.size(32.dp), tint = Color.White)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("200 yd", style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Bold)
                    Text("King's Road", style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.92f))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).height(10.dp).background(LocalRouteColour.current, RoundedCornerShape(5.dp)))
                Spacer(Modifier.width(16.dp))
                Text("13 min", style = MaterialTheme.typography.headlineSmall.merge(Numbers), color = NavColors.eta(), fontWeight = FontWeight.Bold)
            }
        }
    }
}
