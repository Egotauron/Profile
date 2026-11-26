package app.waypoint

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.waypoint.bluetooth.OpenOnConnect
import app.waypoint.bluetooth.OpenOnConnectRule
import app.waypoint.bluetooth.OpenOnConnectService
import app.waypoint.data.AppSettings
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Opening Waypoint when your car's Bluetooth connects. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class OpenOnConnectTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val car = OpenOnConnect("AA:BB:CC:DD:EE:FF", "My car")

    @Test
    fun onlyTheChosenDeviceOpensWaypoint() {
        assertTrue(OpenOnConnectRule.shouldOpen(car, "aa:bb:cc:dd:ee:ff", 0, null))
        assertTrue(OpenOnConnectRule.shouldOpen(car, null, 0, null)) // Android 16's event has no address
        assertFalse(OpenOnConnectRule.shouldOpen(car, "11:22:33:44:55:66", 0, null))
        assertFalse(OpenOnConnectRule.shouldOpen(null, car.address, 0, null))
    }

    @Test
    fun reconnectingAsYouGetInOpensItOnce() {
        val opened = 1_000_000L
        assertFalse(OpenOnConnectRule.shouldOpen(car, car.address, opened + 30_000, opened))
        assertTrue(OpenOnConnectRule.shouldOpen(car, car.address, opened + OpenOnConnectRule.GAP_MS, opened))
    }

    @Test
    fun onlyAndroidsCompanionServiceCanWakeIt() {
        val info = app.packageManager.getServiceInfo(ComponentName(app, OpenOnConnectService::class.java), 0)
        assertEquals("android.permission.BIND_COMPANION_DEVICE_SERVICE", info.permission)
        val found = app.packageManager.queryIntentServices(Intent("android.companion.CompanionDeviceService").setPackage(app.packageName), 0)
        assertTrue(found.any { it.serviceInfo.name == OpenOnConnectService::class.java.name })
        val requested = app.packageManager.getPackageInfo(app.packageName, android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertTrue(Manifest.permission.BLUETOOTH_CONNECT in requested)
        assertTrue(Manifest.permission.REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE in requested)
    }

    @Test
    fun theChoiceIsSavedAndOlderSettingsStillLoad() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        assertNull(json.decodeFromString(AppSettings.serializer(), """{"tiltedMap":false}""").openOnConnect)
        val saved = json.encodeToString(AppSettings.serializer(), AppSettings(openOnConnect = car))
        assertEquals(car, json.decodeFromString(AppSettings.serializer(), saved).openOnConnect)
    }
}
