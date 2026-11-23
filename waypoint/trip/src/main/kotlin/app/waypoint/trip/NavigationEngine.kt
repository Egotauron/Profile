package app.waypoint.trip

import kotlin.time.Duration

/** Live progress along the route being guided. */
data class GuidanceProgress(
    val position: LatLng?,
    /** Time to the end of this route. */
    val durationRemaining: Duration,
    val distanceRemainingMeters: Double,
)

/**
 * Turn-by-turn guidance along one leg's route (spec §2.2). The app's implementation is Ferrostar;
 * the trip runtime only needs to start guidance, hear about progress and know when you've arrived.
 */
interface NavigationEngine {
    /**
     * Guides along [route] (exactly as previewed) and returns once you've arrived at its end.
     * [onProgress] receives live updates while guiding.
     */
    suspend fun guide(route: NavRoute, destination: String, onProgress: (GuidanceProgress) -> Unit)

    /** Swaps the route being guided without ending the session (e.g. the drop-off dock is full). */
    fun replaceRoute(route: NavRoute)

    /** Ends guidance (arrived at a stop, a non-guided segment, or the trip ended). */
    fun stop()
}
