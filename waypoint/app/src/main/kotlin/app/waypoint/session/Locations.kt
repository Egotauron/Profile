package app.waypoint.session

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import app.waypoint.trip.LatLng
import com.stadiamaps.ferrostar.core.location.AndroidLocationProvider
import com.stadiamaps.ferrostar.core.location.NavigationLocationProviding
import com.stadiamaps.ferrostar.core.location.SimulatedLocationProvider
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

fun Context.hasLocationPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

fun Location.toLatLng() = LatLng(latitude, longitude)

/** One fresh fix (or the last known one) for planning from "Current location". */
@SuppressLint("MissingPermission")
suspend fun Context.currentLocation(timeoutMs: Long = 8_000): LatLng? {
    if (!hasLocationPermission()) return null
    val lm = getSystemService(LocationManager::class.java)
    val providers = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && lm.isProviderEnabled(LocationManager.FUSED_PROVIDER)) add(LocationManager.FUSED_PROVIDER)
        if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
        if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
    }
    val fresh = providers.firstOrNull()?.let { provider ->
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Location?> { cont ->
                val signal = androidx.core.os.CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                LocationManagerCompat.getCurrentLocation(lm, provider, signal, Executors.newSingleThreadExecutor()) { loc -> cont.resume(loc) }
            }
        }
    }
    val last = lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
    return (fresh ?: last)?.toLatLng()
}

/**
 * Real GPS, or Ferrostar's simulator moving along the current route (Settings → Simulate
 * location) so the whole trip can be tried at home.
 */
class SwitchableLocationProvider(context: Context, simulate: Boolean) : NavigationLocationProviding {
    val real = AndroidLocationProvider(context)
    val simulated = SimulatedLocationProvider()
    private val mode = MutableStateFlow(simulate)

    var simulate: Boolean
        get() = mode.value
        set(value) { mode.value = value }

    private val active: NavigationLocationProviding get() = if (mode.value) simulated else real

    override suspend fun lastLocation(): Location? = active.lastLocation()

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun locationUpdates(intervalMillis: Long): Flow<Location> =
        mode.flatMapLatest { sim -> (if (sim) simulated else real).locationUpdates(intervalMillis) }
}
