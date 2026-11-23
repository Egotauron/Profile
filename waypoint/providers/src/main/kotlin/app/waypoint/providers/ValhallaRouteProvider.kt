package app.waypoint.providers

import app.waypoint.trip.DistanceUnits
import app.waypoint.trip.NavRoute
import app.waypoint.trip.OsrmSplitter
import app.waypoint.trip.ProviderException
import app.waypoint.trip.RouteOptions
import app.waypoint.trip.RouteProvider
import app.waypoint.trip.RouteRequest
import app.waypoint.trip.RouteResult
import app.waypoint.trip.TravelMode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Valhalla routing (spec §2.1): Stadia Maps' hosted Valhalla with your free API key, or the public
 * FOSSGIS server (valhalla1.openstreetmap.de) when no key is set.
 *
 * The request body matches what Ferrostar's own Valhalla adapter sends (OSRM format, banner and
 * voice instructions, the same shape attributes), so the response is exactly what Ferrostar expects.
 * A trip longer than the server's location limit is split into consecutive chunks.
 */
class ValhallaRouteProvider(
    val endpoint: Endpoint,
    private val client: OkHttpClient = defaultHttpClient(),
) : RouteProvider {

    data class Endpoint(val url: String, val maxLocations: Int, val name: String) {
        companion object {
            fun stadia(apiKey: String) = Endpoint("https://api.stadiamaps.com/route/v1?api_key=$apiKey", 10, "Stadia Maps")
            val FOSSGIS = Endpoint("https://valhalla1.openstreetmap.de/route", 10, "FOSSGIS public Valhalla")
        }
    }

    override suspend fun route(request: RouteRequest): RouteResult = coroutineScope {
        val chunks = chunk(request.points, endpoint.maxLocations)
        val results = chunks.map { pts -> async { routeChunk(request.mode, pts, request.options) } }.awaitAll()
        RouteResult(
            legs = results.flatMap { it.legs },
            exclusionsIgnored = results.any { it.exclusionsIgnored },
        )
    }

    private suspend fun routeChunk(mode: TravelMode, points: List<app.waypoint.trip.LatLng>, options: RouteOptions): RouteResult {
        val costing = costingFor(mode)
        val opts = requestOptions(costing, options, withExclusions = true)
        val (code, body) = post(requestBody(costing, points, opts))
        if (code in 200..299) return RouteResult(parse(costing, opts, body))

        // The public server limits the avoid-polygon perimeter (the charge zone is ~20 km round):
        // route without the exclusion and let the planner warn about it.
        if (options.excludePolygons.isNotEmpty() && "PerimeterExceeded" in body) {
            val plain = requestOptions(costing, options, withExclusions = false)
            val (code2, body2) = post(requestBody(costing, points, plain))
            if (code2 in 200..299) return RouteResult(parse(costing, plain, body2), exclusionsIgnored = true)
            throw ProviderException(errorMessage(code2, body2))
        }
        throw ProviderException(errorMessage(code, body))
    }

    private suspend fun post(body: String): Pair<Int, String> = client.fetch(
        Request.Builder().url(endpoint.url).post(body.toRequestBody("application/json".toMediaType())).build(),
        endpoint.name,
    )

    private fun parse(costing: String, options: JsonObject, body: String): List<NavRoute> = try {
        OsrmSplitter.split(body).map { leg ->
            NavRoute(
                costing = costing,
                optionsJson = options.toString(),
                osrmRouteJson = leg.routeJson,
                osrmWaypointsJson = leg.waypointsJson,
                geometry = leg.geometry,
                distanceMeters = leg.distanceMeters,
                durationSeconds = leg.durationSeconds,
            )
        }
    } catch (e: OsrmSplitter.OsrmException) {
        throw ProviderException("${endpoint.name}: ${e.message}", e)
    } catch (e: Exception) {
        throw ProviderException("${endpoint.name}: unreadable route", e)
    }

    private fun errorMessage(code: Int, body: String): String {
        val msg = runCatching { lenientJson.parseToJsonElement(body).obj?.get("message").str }.getOrNull()
        return "${endpoint.name}: ${msg ?: "HTTP $code"}"
    }

    companion object {
        /** Valhalla's 0–1 preference for roads over cycleways; low = bike paths first. */
        const val BICYCLE_USE_ROADS = 0.1

        fun costingFor(mode: TravelMode): String = when (mode) {
            TravelMode.DRIVE -> "auto"
            TravelMode.WALK -> "pedestrian"
            TravelMode.CYCLE -> "bicycle"
            TravelMode.TRANSIT -> throw IllegalArgumentException("Valhalla doesn't route $mode")
        }

        /** Consecutive windows sharing their end points: 1..10, 10..19, ... */
        fun <T> chunk(points: List<T>, maxLocations: Int): List<List<T>> {
            require(maxLocations >= 2)
            if (points.size <= maxLocations) return listOf(points)
            val out = mutableListOf<List<T>>()
            var start = 0
            while (start < points.lastIndex) {
                val end = minOf(start + maxLocations - 1, points.lastIndex)
                out += points.subList(start, end + 1)
                start = end
            }
            return out
        }

        /**
         * Everything except the locations: costing options, exclusions, units and language. These are
         * also handed to Ferrostar so off-route recalculation uses the same settings.
         */
        fun requestOptions(costing: String, options: RouteOptions, withExclusions: Boolean): JsonObject = buildJsonObject {
            put("units", if (options.distanceUnits == DistanceUnits.MILES) "miles" else "kilometers")
            put("language", options.language)
            putJsonObject("costing_options") {
                putJsonObject(costing) {
                    if (options.avoidFerries) put("use_ferry", 0.0)
                    // Cycling sticks to bike paths and quiet streets where it can.
                    if (costing == "bicycle") put("use_roads", BICYCLE_USE_ROADS)
                    if (costing == "auto") {
                        if (options.avoidTolls) put("use_tolls", 0.0)
                        if (options.avoidMotorways) put("use_highways", 0.0)
                    }
                }
            }
            if (withExclusions && options.excludePolygons.isNotEmpty()) {
                putJsonArray("exclude_polygons") {
                    for (poly in options.excludePolygons) {
                        val ring = if (poly.first() == poly.last()) poly else poly + poly.first()
                        add(buildJsonArray { for (p in ring) add(buildJsonArray { add(JsonPrimitive(p.lng)); add(JsonPrimitive(p.lat)) }) })
                    }
                }
            }
        }

        /** Same shape as Ferrostar's ValhallaHttpRequestGenerator, plus [options]. */
        fun requestBody(costing: String, points: List<app.waypoint.trip.LatLng>, options: JsonObject): String = buildJsonObject {
            put("format", "osrm")
            putJsonObject("filters") {
                put("action", "include")
                putJsonArray("attributes") {
                    add(JsonPrimitive("shape_attributes.speed"))
                    add(JsonPrimitive("shape_attributes.speed_limit"))
                    add(JsonPrimitive("shape_attributes.time"))
                    add(JsonPrimitive("shape_attributes.length"))
                }
            }
            put("banner_instructions", true)
            put("voice_instructions", true)
            put("costing", costing)
            put("locations", JsonArray(points.mapIndexed { i, p ->
                buildJsonObject {
                    put("lat", p.lat)
                    put("lon", p.lng)
                    if (i == 0) put("street_side_tolerance", 5) else put("type", "break")
                }
            }))
            for ((k, v) in options) put(k, v)
        }.toString()
    }
}
