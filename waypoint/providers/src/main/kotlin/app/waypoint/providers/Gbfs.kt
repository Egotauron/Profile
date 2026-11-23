package app.waypoint.providers

import app.waypoint.trip.BikeShareProvider
import app.waypoint.trip.Dock
import app.waypoint.trip.Geo
import app.waypoint.trip.LatLng
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient

/**
 * Any docked bike-share system that publishes GBFS (v2 or v3): give it the system's `gbfs.json`
 * auto-discovery URL. Station list and live status are cached for a minute.
 */
class GbfsBikeShareProvider(
    private val discoveryUrl: String,
    private val systemName: String,
    private val client: OkHttpClient = defaultHttpClient(),
    private val cacheFor: Duration = 60.seconds,
) : BikeShareProvider {
    private val mutex = Mutex()
    private var feeds: Map<String, String>? = null
    private var cached: Pair<TimeMark, List<Dock>>? = null

    override suspend fun docksNear(point: LatLng, radiusMeters: Double): List<Dock> =
        docks().filter { Geo.distanceMeters(it.latLng, point) <= radiusMeters }

    suspend fun docks(): List<Dock> = mutex.withLock {
        cached?.let { (mark, docks) -> if (mark.elapsedNow() < cacheFor) return@withLock docks }
        val urls = feeds ?: parseDiscovery(client.getJson(discoveryUrl, systemName)).also { feeds = it }
        val info = urls["station_information"] ?: return@withLock emptyList()
        val status = urls["station_status"] ?: return@withLock emptyList()
        val (i, s) = coroutineScope {
            listOf(async { client.getJson(info, systemName) }, async { client.getJson(status, systemName) }).awaitAll()
        }
        join(i, s, systemName).also { cached = TimeSource.Monotonic.markNow() to it }
    }

    companion object {
        /** feed name → URL, from a v2 (`data.<lang>.feeds`) or v3 (`data.feeds`) auto-discovery file. */
        fun parseDiscovery(root: JsonElement): Map<String, String> {
            val data = root.obj?.get("data").obj ?: return emptyMap()
            val feeds = data["feeds"]?.arr?.takeIf { it.isNotEmpty() }
                ?: (data["en"]?.obj ?: data.values.firstOrNull()?.obj)?.get("feeds").arr
            return feeds.mapNotNull { f ->
                val o = f.obj ?: return@mapNotNull null
                (o["name"].str ?: return@mapNotNull null) to (o["url"].str ?: return@mapNotNull null)
            }.toMap()
        }

        private fun name(e: JsonElement?): String =
            e.str ?: e.arr.firstOrNull { it.obj?.get("language").str?.startsWith("en") == true }?.obj?.get("text").str
                ?: e.arr.firstOrNull()?.obj?.get("text").str.orEmpty()

        fun join(information: JsonElement, status: JsonElement, system: String): List<Dock> {
            val live = status.obj?.get("data").obj?.get("stations").arr.mapNotNull { s ->
                val o = s.obj ?: return@mapNotNull null
                (o["station_id"].str ?: return@mapNotNull null) to o
            }.toMap()
            return information.obj?.get("data").obj?.get("stations").arr.mapNotNull { s ->
                val o = s.obj ?: return@mapNotNull null
                val id = o["station_id"].str ?: return@mapNotNull null
                val st = live[id] ?: return@mapNotNull null
                val renting = st["is_renting"].bool ?: (st["is_renting"].int?.let { it == 1 } ?: true)
                val returning = st["is_returning"].bool ?: (st["is_returning"].int?.let { it == 1 } ?: true)
                Dock(
                    id = id,
                    name = name(o["name"]),
                    latLng = LatLng(o["lat"].num ?: return@mapNotNull null, o["lon"].num ?: return@mapNotNull null),
                    bikesAvailable = if (renting) (st["num_bikes_available"].int ?: st["num_vehicles_available"].int ?: 0) else 0,
                    spacesAvailable = if (returning) (st["num_docks_available"].int ?: 0) else 0,
                    system = system,
                )
            }
        }
    }
}

/** Docks from every configured system (TfL BikePoint + any GBFS feeds). */
class CompositeBikeShareProvider(private val systems: List<BikeShareProvider>) : BikeShareProvider {
    override suspend fun docksNear(point: LatLng, radiusMeters: Double): List<Dock> = coroutineScope {
        systems.map { async { runCatching { it.docksNear(point, radiusMeters) }.getOrDefault(emptyList()) } }
            .awaitAll().flatten()
    }
}
