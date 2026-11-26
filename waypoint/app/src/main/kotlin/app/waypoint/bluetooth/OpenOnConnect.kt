package app.waypoint.bluetooth

import android.Manifest
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import app.waypoint.MainActivity
import app.waypoint.graph
import kotlinx.serialization.Serializable

/** The Bluetooth device (your car, say) that opens Waypoint when it connects. */
@Serializable
data class OpenOnConnect(val address: String, val name: String)

/** When a connection opens Waypoint: only the chosen device, and once per [GAP_MS]. */
object OpenOnConnectRule {
    /** Bluetooth can drop and reconnect as you get in; Waypoint opens once. */
    const val GAP_MS = 2 * 60_000L

    fun shouldOpen(chosen: OpenOnConnect?, address: String?, nowMs: Long, lastOpenedMs: Long?): Boolean =
        chosen != null &&
            (address == null || address.equals(chosen.address, ignoreCase = true)) &&
            (lastOpenedMs == null || nowMs - lastOpenedMs >= GAP_MS)
}

/**
 * Opening Waypoint when a Bluetooth device connects, through Android's companion devices: once
 * you link a paired device (Android asks you to confirm), Android tells Waypoint when it connects
 * ([OpenOnConnectService]) and lets it open itself then, which apps otherwise can't from the
 * background. Android 12 or later.
 *
 * Android only links an already-paired device when it's named by its address, so Waypoint lists
 * your paired devices first, which takes the Nearby devices permission.
 */
class BluetoothLaunch(private val context: Context) {
    private val companions get() = context.getSystemService(CompanionDeviceManager::class.java)

    val supported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)

    fun hasPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    data class Paired(val name: String, val address: String, val car: Boolean)

    /** Your paired devices, cars first. Empty without the permission or with Bluetooth off. */
    @RequiresApi(Build.VERSION_CODES.S)
    fun paired(): List<Paired> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        return runCatching {
            adapter.bondedDevices.orEmpty().map { d ->
                Paired(
                    name = d.alias ?: d.name ?: d.address,
                    address = d.address,
                    car = d.bluetoothClass?.deviceClass in CAR_CLASSES,
                )
            }
        }.getOrDefault(emptyList()).sortedWith(compareByDescending<Paired> { it.car }.thenBy { it.name.lowercase() })
    }

    /** Asks Android to link [device]; [onConfirm] gets its confirmation screen to show. */
    @RequiresApi(Build.VERSION_CODES.S)
    fun link(device: Paired, onConfirm: (IntentSender) -> Unit, onFailure: (String) -> Unit) {
        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder().setAddress(device.address).build())
            .setSingleDevice(true)
            .build()
        val callback = object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) = onConfirm(intentSender)

            @Deprecated("Android 12 calls this instead of onAssociationPending")
            override fun onDeviceFound(intentSender: IntentSender) = onConfirm(intentSender)

            override fun onFailure(error: CharSequence?) = onFailure(error?.toString() ?: "no reason given")
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                companions.associate(request, ContextCompat.getMainExecutor(context), callback)
            } else {
                @Suppress("DEPRECATION")
                companions.associate(request, callback, Handler(Looper.getMainLooper()))
            }
        }.onFailure { onFailure(it.message ?: it.javaClass.simpleName) }
    }

    /** After you confirm: Android tells Waypoint each time it connects. */
    @RequiresApi(Build.VERSION_CODES.S)
    fun watch(address: String): Boolean = runCatching {
        @Suppress("DEPRECATION")
        companions.startObservingDevicePresence(address)
    }.isSuccess

    /** Unlinks [address]: no more opening, and Android forgets the link. */
    @RequiresApi(Build.VERSION_CODES.S)
    fun unlink(address: String) {
        runCatching {
            @Suppress("DEPRECATION")
            companions.stopObservingDevicePresence(address)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                companions.myAssociations.filter { it.deviceMacAddress?.toString().equals(address, ignoreCase = true) }
                    .forEach { companions.disassociate(it.id) }
            } else {
                @Suppress("DEPRECATION")
                companions.disassociate(address)
            }
        }
    }

    /** Still linked (you can remove the link in Android's settings too). */
    @RequiresApi(Build.VERSION_CODES.S)
    fun linked(address: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            companions.myAssociations.any { it.deviceMacAddress?.toString().equals(address, ignoreCase = true) }
        } else {
            @Suppress("DEPRECATION")
            companions.associations.any { it.equals(address, ignoreCase = true) }
        }
    }.getOrDefault(false)

    private companion object {
        val CAR_CLASSES = setOf(BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO, BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE)
    }
}

/** Android wakes this when the linked device connects; it opens Waypoint. */
@RequiresApi(Build.VERSION_CODES.S)
class OpenOnConnectService : CompanionDeviceService() {
    @Deprecated("Android 12 and the address-based watch call this")
    override fun onDeviceAppeared(address: String) = open(address)

    // Android 13+ passes the link; its default calls the address version above for devices like
    // this one, so it isn't overridden.

    @RequiresApi(36)
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        if (event.event == DevicePresenceEvent.EVENT_BT_CONNECTED) open(null)
    }

    private fun open(address: String?) {
        val chosen = applicationContext.graph.settings.current.openOnConnect
        val now = SystemClock.elapsedRealtime()
        if (!OpenOnConnectRule.shouldOpen(chosen, address, now, lastOpened)) return
        lastOpened = now
        runCatching { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private companion object {
        var lastOpened: Long? = null
    }
}
