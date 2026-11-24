package app.waypoint.session

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import app.waypoint.trip.LatLng
import com.stadiamaps.ferrostar.core.location.NavigationLocationProviding
import com.stadiamaps.ferrostar.core.location.SimulatedLocationProvider
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onEach
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
 * GPS for navigation. Ferrostar's own provider asks for the fused provider through Android's old
 * API, which quietly runs it in low-power mode: Wi-Fi and cell fixes, often worse than 15 m (too
 * vague to tell you've left the route), usually with no direction of travel (so a new route may
 * start with a U-turn) and at an uneven rate (a jerky car on the map). This asks for high accuracy,
 * twice a second while navigating.
 */
class GpsLocationProvider(private val context: Context) : NavigationLocationProviding {
    private val lm = context.getSystemService(LocationManager::class.java)

    private fun provider(): String {
        val enabled = lm.getProviders(true).toSet()
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && LocationManager.FUSED_PROVIDER in enabled -> LocationManager.FUSED_PROVIDER
            LocationManager.GPS_PROVIDER in enabled -> LocationManager.GPS_PROVIDER
            LocationManager.NETWORK_PROVIDER in enabled -> LocationManager.NETWORK_PROVIDER
            else -> LocationManager.PASSIVE_PROVIDER
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun lastLocation(): Location? =
        if (context.hasLocationPermission()) runCatching { lm.getLastKnownLocation(provider()) }.getOrNull() else null

    @SuppressLint("MissingPermission")
    override fun locationUpdates(intervalMillis: Long): Flow<Location> = callbackFlow {
        if (!context.hasLocationPermission()) {
            awaitClose()
            return@callbackFlow
        }
        val provider = provider()
        val listener = LocationListenerCompat { trySend(it) }
        // A recent fix straight away, so the map doesn't wait for the first new one.
        lastLocation()?.takeIf { SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos < STALE_NANOS }?.let { trySend(it) }
        // Navigation asks for one a second; let chips that can go faster do so.
        val interval = if (intervalMillis <= 1_000) NAVIGATION_INTERVAL_MS else intervalMillis
        val request = LocationRequestCompat.Builder(interval)
            .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
            // Navigation takes every fix it can get; slower requests (the map dot) stay slow.
            .setMinUpdateIntervalMillis(if (interval <= NAVIGATION_INTERVAL_MS) 0 else interval)
            .build()
        LocationManagerCompat.requestLocationUpdates(lm, provider, request, ContextCompat.getMainExecutor(context), listener)
        awaitClose { LocationManagerCompat.removeUpdates(lm, listener) }
    }

    companion object {
        const val NAVIGATION_INTERVAL_MS = 500L
        private const val STALE_NANOS = 30_000_000_000L
    }
}

/**
 * Real GPS, or Ferrostar's simulator moving along the current route (Settings → Simulate
 * location) so the whole trip can be tried at home. Also remembers the last few fixes, to work out
 * which way you're going when a fix comes without a direction.
 */
class SwitchableLocationProvider(context: Context, simulate: Boolean) : NavigationLocationProviding {
    val real = GpsLocationProvider(context)
    val simulated = SimulatedLocationProvider()
    private val mode = MutableStateFlow(simulate)
    private val recent = ArrayDeque<Location>()

    var simulate: Boolean
        get() = mode.value
        set(value) { mode.value = value }

    private val active: NavigationLocationProviding get() = if (mode.value) simulated else real

    override suspend fun lastLocation(): Location? = active.lastLocation()

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun locationUpdates(intervalMillis: Long): Flow<Location> =
        mode.flatMapLatest { sim -> (if (sim) simulated else real).locationUpdates(intervalMillis) }
            .onEach { record(it) }

    @Synchronized
    private fun record(loc: Location) {
        // Two subscribers (navigation and the map dot) can see the same fix.
        if (recent.lastOrNull()?.let { it.time == loc.time && it.latitude == loc.latitude && it.longitude == loc.longitude } == true) return
        recent.addLast(loc)
        while (recent.size > 20) recent.removeFirst()
    }

    /**
     * Whether you're standing still: the GPS's own speed when it gives one (even without saying
     * how accurate it is), else whether the fixes of the last few seconds stayed within a few
     * metres of each other. GPS drift alone mustn't move, spin or re-route the map.
     */
    @Synchronized
    fun isStill(): Boolean = Motion.isStill(recent.toList())

    /**
     * The direction you've been moving in (degrees from north), from the newest fix and the last
     * one at least 10 m before it in the past 20 s; null when you've hardly moved.
     */
    @Synchronized
    fun recentCourse(): Double? {
        val newest = recent.lastOrNull() ?: return null
        val earlier = recent.reversed().drop(1).firstOrNull { newest.distanceTo(it) >= 10f } ?: return null
        if (newest.time - earlier.time > 20_000) return null
        return ((earlier.bearingTo(newest).toDouble()) + 360.0) % 360.0
    }
}

/** How still-or-moving is decided from raw fixes (kept apart so it can be tested). */
object Motion {
    /** Slower than this (m/s), by the GPS's own speed, is standing still (a slow walk is ~1.1). */
    const val STILL_MPS = 0.7f
    private const val WINDOW_MS = 4_000L

    fun isStill(fixes: List<Location>): Boolean {
        val newest = fixes.lastOrNull() ?: return false
        if (newest.hasSpeed()) return newest.speed < STILL_MPS
        val window = fixes.filter { newest.time - it.time in 0..WINDOW_MS }
        if (window.size < 3) return false
        val radius = maxOf(10f, if (newest.hasAccuracy()) newest.accuracy else 10f)
        return window.all { it.distanceTo(newest) <= radius }
    }
}
