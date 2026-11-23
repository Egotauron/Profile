package app.waypoint

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import app.waypoint.data.AppSettings
import app.waypoint.data.Repository
import app.waypoint.data.SettingsStore
import app.waypoint.data.WaypointDatabase
import app.waypoint.music.MusicController
import app.waypoint.providers.CompositeTransitProvider
import app.waypoint.providers.PhotonPlaceProvider
import app.waypoint.providers.TflClient
import app.waypoint.providers.TflTransitProvider
import app.waypoint.providers.TransitousTransitProvider
import app.waypoint.providers.ValhallaRouteProvider
import app.waypoint.providers.defaultHttpClient
import app.waypoint.session.TripSession
import app.waypoint.trip.DriveArea
import app.waypoint.trip.DriveTimeCalibration
import app.waypoint.trip.TimeBucket
import app.waypoint.trip.TransitProvider
import app.waypoint.trip.TripPlanner
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class WaypointApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TRIP, getString(R.string.channel_trip), NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH),
        )
    }

    companion object {
        const val CHANNEL_TRIP = "trip"
        const val CHANNEL_ALERTS = "alerts"
    }
}

val Context.graph: AppGraph get() = (applicationContext as WaypointApp).graph

/** Hand-rolled dependency graph: one instance of each service for the whole app. */
class AppGraph(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsStore(app)
    val repo = Repository(WaypointDatabase.create(app).dao())
    val http = defaultHttpClient()
    val places = PhotonPlaceProvider(client = http)

    private val _calibration = MutableStateFlow(DriveTimeCalibration())
    val calibration: StateFlow<DriveTimeCalibration> = _calibration.asStateFlow()

    init {
        scope.launch { _calibration.value = repo.calibration() }
    }

    val routeEndpoint: ValhallaRouteProvider.Endpoint
        get() = settings.current.let {
            if (it.usesStadia) ValhallaRouteProvider.Endpoint.stadia(it.stadiaApiKey.trim()) else ValhallaRouteProvider.Endpoint.FOSSGIS
        }

    fun routes() = ValhallaRouteProvider(routeEndpoint, http)

    fun tfl() = TflClient(settings.current.tflAppKey.trim().ifBlank { null }, client = http)

    fun transit(): TransitProvider = CompositeTransitProvider(TflTransitProvider(tfl()), TransitousTransitProvider(client = http))

    fun planner() = TripPlanner(
        routes = routes(),
        transit = transit(),
        calibration = { _calibration.value },
        settings = { settings.current.plannerSettings() },
    )

    fun recordDrive(area: DriveArea, bucket: TimeBucket, planned: Duration, actual: Duration) {
        val next = _calibration.value.record(area, bucket, planned, actual)
        _calibration.value = next
        scope.launch { repo.saveCalibration(next) }
    }

    fun resetCalibration() {
        _calibration.value = DriveTimeCalibration()
        scope.launch { repo.saveCalibration(_calibration.value) }
    }

    val music: MusicController by lazy { MusicController(app, settings) }

    val session: TripSession by lazy { TripSession(app, this) }
}
