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
    /** Your car meets the ULEZ standards (most petrol cars from 2006 and diesels from 2015 do). */
    val ulezCompliantCar: Boolean = true,
    val units: DistanceUnits = DistanceUnits.MILES,
    /** Older on/off voice switch; off still wins (see [voice]). */
    val voiceGuidance: Boolean = true,
    /** On, quiet (road changes only) or off. */
    val voiceMode: app.waypoint.session.VoiceMode = app.waypoint.session.VoiceMode.ON,
    /** Read the Auto countdowns out ("Continuing to the Ashmolean in 2 minutes"). */
    val speakCountdowns: Boolean = true,
    /** Package of the player the music chip opens when nothing is playing. */
    val defaultMusicPackage: String? = null,
    /** Music when a trip starts: nothing, resume the last player, or a chosen playlist. */
    val tripStartMusic: app.waypoint.music.TripStartMusic = app.waypoint.music.TripStartMusic.NOTHING,
    val tripStartPlaylist: app.waypoint.music.PlaylistChoice? = null,
    /** Developer option: move along the route instead of using GPS. */
    val simulateLocation: Boolean = false,
    /** Tilted (3D) view while following you on the navigation map; flat when off. */
    val tiltedMap: Boolean = true,
    /** A line of GPS details on the navigation map, for diagnosing map jumps. */
    val showGpsDetails: Boolean = false,
) {
    val usesStadia: Boolean get() = stadiaApiKey.isNotBlank()

    /** The navigation voice: off when either switch says so. */
    val voice: app.waypoint.session.VoiceMode
        get() = if (!voiceGuidance) app.waypoint.session.VoiceMode.OFF else voiceMode

    fun withVoice(mode: app.waypoint.session.VoiceMode) = copy(voiceMode = mode, voiceGuidance = mode != app.waypoint.session.VoiceMode.OFF)

    fun plannerSettings(): PlannerSettings = PlannerSettings(
        defaultParkingTime = defaultParkingTime,
        avoidChargeZone = fastestDefaults.avoidChargeZone,
        ulezCompliantCar = ulezCompliantCar,
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
