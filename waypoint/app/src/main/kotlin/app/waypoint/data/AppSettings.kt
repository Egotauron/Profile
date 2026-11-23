package app.waypoint.data

import android.content.Context
import app.waypoint.BuildConfig
import app.waypoint.trip.Continuation
import app.waypoint.trip.DistanceUnits
import app.waypoint.trip.FastestOptions
import app.waypoint.trip.PlannerSettings
import app.waypoint.trip.RouteOptions
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Everything on the Settings screen (spec §6.7). */
@Serializable
data class AppSettings(
    /** Stadia Maps free key (non-commercial). Empty = the public FOSSGIS Valhalla server. */
    val stadiaApiKey: String = BuildConfig.STADIA_API_KEY,
    /** TfL Unified API key; optional (raises the 50 requests/minute anonymous limit). */
    val tflAppKey: String = BuildConfig.TFL_APP_KEY,
    val defaultContinuation: Continuation = Continuation.Auto,
    val defaultStay: Duration = Duration.ZERO,
    val defaultParkingTime: Duration = 5.minutes,
    val fastestDefaults: FastestOptions = FastestOptions(),
    val avoidTolls: Boolean = false,
    val avoidMotorways: Boolean = false,
    val avoidFerries: Boolean = false,
    val units: DistanceUnits = DistanceUnits.MILES,
    val voiceGuidance: Boolean = true,
    /** Read the Auto countdowns out ("Continuing to the Ashmolean in 2 minutes"). */
    val speakCountdowns: Boolean = true,
    /** Santander Cycles (TfL BikePoint). */
    val santanderCycles: Boolean = true,
    /** Extra docked bike-share systems: GBFS auto-discovery URLs. */
    val gbfsFeeds: List<String> = emptyList(),
    /** Package of the player the music chip opens when nothing is playing. */
    val defaultMusicPackage: String? = null,
    /** Developer option: move along the route instead of using GPS. */
    val simulateLocation: Boolean = false,
) {
    val usesStadia: Boolean get() = stadiaApiKey.isNotBlank()

    fun plannerSettings(): PlannerSettings = PlannerSettings(
        defaultParkingTime = defaultParkingTime,
        avoidChargeZone = fastestDefaults.avoidChargeZone,
        routeOptions = RouteOptions(
            avoidTolls = avoidTolls,
            avoidMotorways = avoidMotorways,
            avoidFerries = avoidFerries,
            distanceUnits = units,
            language = "en-GB",
        ),
    )
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()
    val current: AppSettings get() = _settings.value

    private fun load(): AppSettings = prefs.getString(KEY, null)
        ?.let { runCatching { json.decodeFromString(AppSettings.serializer(), it) }.getOrNull() }
        ?: AppSettings()

    fun update(change: (AppSettings) -> AppSettings) {
        val next = change(_settings.value)
        _settings.value = next
        prefs.edit().putString(KEY, json.encodeToString(AppSettings.serializer(), next)).apply()
    }

    private companion object {
        const val KEY = "settings.v1"
    }
}
