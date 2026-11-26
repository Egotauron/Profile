package app.waypoint.ui

import android.Manifest
import android.app.Activity
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.waypoint.bluetooth.BluetoothLaunch
import app.waypoint.bluetooth.OpenOnConnect

/** Settings: open Waypoint when a Bluetooth device (your car) connects. */
@Composable
fun OpenOnConnectSettings(chosen: OpenOnConnect?, onChange: (OpenOnConnect?) -> Unit) {
    val context = LocalContext.current
    val bluetooth = remember { BluetoothLaunch(context.applicationContext) }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && bluetooth.supported) {
        OpenOnConnectControls(bluetooth, chosen, onChange)
    } else {
        Text(
            "Opening Waypoint when Bluetooth connects needs Android 12 or later.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@RequiresApi(Build.VERSION_CODES.S)
@Composable
private fun OpenOnConnectControls(bluetooth: BluetoothLaunch, chosen: OpenOnConnect?, onChange: (OpenOnConnect?) -> Unit) {
    val context = LocalContext.current
    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    var picking by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<BluetoothLaunch.Paired?>(null) }
    val linked = remember(chosen) { chosen == null || bluetooth.linked(chosen.address) }

    // Android's own "Allow Waypoint to access …?" confirmation.
    val confirm = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val device = pending ?: return@rememberLauncherForActivityResult
        pending = null
        if (result.resultCode != Activity.RESULT_OK) {
            toast("${device.name} wasn't linked")
            return@rememberLauncherForActivityResult
        }
        chosen?.takeIf { !it.address.equals(device.address, ignoreCase = true) }?.let { bluetooth.unlink(it.address) }
        if (bluetooth.watch(device.address)) {
            toast("Waypoint will open when ${device.name} connects")
        } else {
            toast("${device.name} is linked, but Android won't say when it connects")
        }
        onChange(OpenOnConnect(device.address, device.name))
    }
    val askNearby = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) picking = true else toast("Waypoint needs the Nearby devices permission to list your paired Bluetooth devices")
    }

    OpenOnConnectRow(
        chosen = chosen,
        linked = linked,
        onChoose = { if (bluetooth.hasPermission()) picking = true else askNearby.launch(Manifest.permission.BLUETOOTH_CONNECT) },
        onTurnOff = {
            chosen?.let { bluetooth.unlink(it.address) }
            onChange(null)
        },
    )

    if (picking) {
        val devices = remember { bluetooth.paired() }
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text("Open Waypoint when…") },
            text = {
                PairedDeviceList(devices) { device ->
                    picking = false
                    pending = device
                    bluetooth.link(
                        device,
                        onConfirm = { confirm.launch(IntentSenderRequest.Builder(it).build()) },
                        onFailure = { reason ->
                            pending = null
                            toast("Android couldn't link ${device.name}: $reason")
                        },
                    )
                }
            },
            confirmButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        )
    }
}

/** The setting as a row: which device, and choosing or turning it off. */
@Composable
fun OpenOnConnectRow(chosen: OpenOnConnect?, linked: Boolean, onChoose: () -> Unit, onTurnOff: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("When Bluetooth connects")
            Text(
                when {
                    chosen == null -> "Off. Choose your car and Waypoint opens when you get in."
                    !linked -> "${chosen.name} was unlinked in Android's settings. Choose it again to turn this back on."
                    else -> "Opens when ${chosen.name} connects. If your phone is locked, it's there when you unlock."
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (chosen != null) TextButton(onClick = onTurnOff) { Text("Turn off") }
        TextButton(onClick = onChoose) { Text(if (chosen == null) "Choose" else "Change") }
    }
}

/** Your paired Bluetooth devices to pick from, cars first. */
@Composable
fun PairedDeviceList(devices: List<BluetoothLaunch.Paired>, onPick: (BluetoothLaunch.Paired) -> Unit) {
    if (devices.isEmpty()) {
        Text(
            "No paired devices. Check Bluetooth is on, and pair your car in Android's Bluetooth settings first.",
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "Android will ask you to confirm. Waypoint is then told when it connects.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        for (device in devices) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick(device) }.padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (device.car) Icons.Filled.DirectionsCar else Icons.Filled.Bluetooth, null,
                    tint = if (device.car) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(14.dp))
                Text(device.name, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
