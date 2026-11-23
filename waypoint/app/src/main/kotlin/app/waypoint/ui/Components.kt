package app.waypoint.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import app.waypoint.trip.Continuation
import kotlin.time.Duration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.waypoint.trip.ModeChoice
import app.waypoint.trip.TransitVehicle
import app.waypoint.trip.TravelMode
import app.waypoint.trip.Warning

/** The amber stop number used on the map and in lists. */
@Composable
fun StopBadge(n: Int, size: Dp = 28.dp) {
    Box(
        Modifier.size(size).background(Palette.Amber, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text("$n", color = Palette.Pine, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.5f).sp)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
fun WarningLine(w: Warning) {
    val info = ModeUi.isInfo(w)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(
            if (info) Icons.Filled.Info else Icons.Filled.WarningAmber, null,
            tint = if (info) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp),
        )
        Text(
            ModeUi.warning(w),
            style = MaterialTheme.typography.bodySmall,
            color = if (info) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
        )
    }
}

/** A small coloured pill with the line name ("14", "Piccadilly"). */
@Composable
fun LineBadge(line: String, vehicle: TransitVehicle) {
    Row(
        Modifier.background(ModeUi.vehicleColor(vehicle), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(ModeUi.vehicleIcon(vehicle), null, tint = Color.White, modifier = Modifier.size(14.dp))
        Text(line, color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    }
}

data class MenuOption<T>(val label: String, val value: T, val icon: ImageVector? = null)

/** A chip that opens a menu of options. */
@Composable
fun <T> MenuChip(
    label: String,
    selected: Boolean,
    options: List<MenuOption<T>>,
    onPick: (T) -> Unit,
    icon: ImageVector? = null,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected,
            onClick = { open = true },
            label = { Text(label) },
            leadingIcon = icon?.let { { Icon(it, null, Modifier.size(18.dp)) } },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(18.dp)) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(
                    text = { Text(o.label) },
                    leadingIcon = o.icon?.let { { Icon(it, null) } },
                    onClick = { open = false; onPick(o.value) },
                )
            }
        }
    }
}

fun modeChoiceLabel(m: ModeChoice?): String = when (m) {
    null -> "Trip default"
    is ModeChoice.Fixed -> ModeUi.label(m.mode)
    is ModeChoice.Fastest -> "Fastest"
}

fun modeChoiceIcon(m: ModeChoice?): ImageVector? = when (m) {
    is ModeChoice.Fixed -> ModeUi.icon(m.mode)
    else -> null
}

val fixedModes = listOf(TravelMode.DRIVE, TravelMode.WALK, TravelMode.TRANSIT, TravelMode.CYCLE)

/** Asked when a stop goes Manual (spec §4.2): roughly how long you'll stay, used only for ETAs. */
@Composable
fun StayPrompt(title: String, initial: Duration, onDismiss: () -> Unit, onPick: (Duration) -> Unit) {
    var selected by remember {
        mutableStateOf(initial.takeIf { it in Continuation.MANUAL_STAY_CHOICES } ?: Continuation.DEFAULT_MANUAL_STAY)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { StayChoices(selected) { selected = it } },
        confirmButton = { TextButton(onClick = { onPick(selected) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The body of [StayPrompt]: the explanation and the 5 / 15 / 30 / 60 min choices. */
@Composable
fun StayChoices(selected: Duration, onSelect: (Duration) -> Unit) {
    Column {
        Text(
            "Roughly how long will you stay? The next leg waits for you to tap Start next leg; this is only for the ETAs.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Continuation.MANUAL_STAY_CHOICES.forEach { d ->
            Row(
                Modifier.fillMaxWidth().selectable(d == selected) { onSelect(d) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = d == selected, onClick = { onSelect(d) })
                Text(Format.duration(d))
            }
        }
    }
}
