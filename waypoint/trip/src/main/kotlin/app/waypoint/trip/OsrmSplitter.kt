package app.waypoint.trip

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Splits one multi-stop Valhalla response (OSRM format, every stop a `break`) into one single-leg
 * route per leg.
 *
 * Ferrostar navigates one route at a time and flattens every leg's steps into it, so the app guides
 * leg by leg: each leg gets its own OSRM route object (that leg's steps, annotation and geometry)
 * plus its two waypoints. Nothing is re-routed, so what you previewed is what you're guided along.
 */
object OsrmSplitter {
    data class LegRoute(
        val routeJson: String,
        val waypointsJson: String,
        val geometry: List<LatLng>,
        val distanceMeters: Double,
        val durationSeconds: Double,
    )

    class OsrmException(message: String) : Exception(message)

    private val json = Json { ignoreUnknownKeys = true }

    fun split(responseJson: String): List<LegRoute> {
        val root = json.parseToJsonElement(responseJson).jsonObject
        val code = root["code"]?.jsonPrimitive?.content
        if (code != "Ok") {
            val message = root["message"]?.jsonPrimitive?.content ?: "routing failed"
            throw OsrmException("$code: $message")
        }
        val route = root["routes"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw OsrmException("NoRoute: the response has no route")
        val legs = route["legs"]!!.jsonArray
        val waypoints = root["waypoints"]!!.jsonArray
        if (waypoints.size != legs.size + 1) {
            throw OsrmException("Expected ${legs.size + 1} waypoints for ${legs.size} legs, got ${waypoints.size}")
        }
        return legs.mapIndexed { i, legElement ->
            val leg = legElement.jsonObject
            val geometry = legGeometry(leg)
            val legRoute = buildJsonObject {
                put("distance", leg["distance"]!!)
                put("duration", leg["duration"]!!)
                leg["weight"]?.let { put("weight", it) }
                route["weight_name"]?.let { put("weight_name", it) }
                route["voiceLocale"]?.let { put("voiceLocale", it) }
                put("geometry", JsonPrimitive(Polyline.encode(geometry)))
                put("legs", JsonArray(listOf(leg)))
            }
            LegRoute(
                routeJson = legRoute.toString(),
                waypointsJson = JsonArray(listOf(waypoints[i], waypoints[i + 1])).toString(),
                geometry = geometry,
                distanceMeters = leg["distance"]!!.jsonPrimitive.double,
                durationSeconds = leg["duration"]!!.jsonPrimitive.double,
            )
        }
    }

    /** The leg's line: its steps' geometries joined end to end. */
    private fun legGeometry(leg: JsonObject): List<LatLng> {
        val out = ArrayList<LatLng>()
        for (step in leg["steps"]!!.jsonArray) {
            val pts = Polyline.decode(step.jsonObject["geometry"]!!.jsonPrimitive.content)
            for (p in pts) if (out.isEmpty() || out.last() != p) out += p
        }
        return out
    }
}
