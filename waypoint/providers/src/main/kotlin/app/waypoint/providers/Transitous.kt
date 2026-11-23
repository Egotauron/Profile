package app.waypoint.providers

import app.waypoint.trip.LatLng
import app.waypoint.trip.Polyline
import app.waypoint.trip.Segment
import app.waypoint.trip.TransitItinerary
import app.waypoint.trip.TransitProvider
import app.waypoint.trip.TransitStop
import app.waypoint.trip.TransitVehicle
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient

/**
 * Transitous (community MOTIS) for transit outside London (spec §2.1). Free and best-effort, so the
 * app identifies itself in the User-Agent.
 */
class TransitousTransitProvider(
    private val baseUrl: String = "https://api.transitous.org",
    private val client: OkHttpClient = defaultHttpClient(),
) : TransitProvider {

    override suspend fun plan(from: LatLng, to: LatLng, departAt: Instant): List<TransitItinerary> {
        val url = "$baseUrl/api/v5/plan?fromPlace=${from.lat},${from.lng}&toPlace=${to.lat},${to.lng}" +
            "&time=$departAt&arriveBy=false&numItineraries=5&transitModes=TRANSIT"
        return parse(client.getJson(url, "Transitous"))
    }

    companion object {
        fun vehicleFor(mode: String): TransitVehicle? = when (mode) {
            "BUS", "COACH", "TROLLEYBUS" -> TransitVehicle.BUS
            "SUBWAY", "METRO" -> TransitVehicle.SUBWAY
            "TRAM", "CABLE_CAR", "FUNICULAR" -> TransitVehicle.TRAM
            "RAIL", "HIGHSPEED_RAIL", "LONG_DISTANCE", "NIGHT_RAIL", "REGIONAL_FAST_RAIL", "REGIONAL_RAIL", "SUBURBAN" -> TransitVehicle.TRAIN
            else -> null
        }

        private fun stop(o: JsonElement?): TransitStop? {
            val p = o.obj ?: return null
            return TransitStop(
                name = p["name"].str.orEmpty(),
                latLng = LatLng(p["lat"].num ?: return null, p["lon"].num ?: return null),
                id = p["stopId"].str,
            )
        }

        private fun geometry(leg: Map<String, JsonElement>): List<LatLng> {
            val g = leg["legGeometry"].obj ?: return emptyList()
            return Polyline.decode(g["points"].str ?: return emptyList(), g["precision"].int ?: 6)
        }

        /** Itineraries with modes we can't filter by (ferries, flights, on-demand) are dropped. */
        fun parse(root: JsonElement): List<TransitItinerary> = root.obj?.get("itineraries").arr.mapNotNull { it ->
            val o = it.obj ?: return@mapNotNull null
            val segments = o["legs"].arr.map { legEl ->
                val l = legEl.obj ?: return@mapNotNull null
                val mode = l["mode"].str ?: return@mapNotNull null
                val from = stop(l["from"]) ?: return@mapNotNull null
                val to = stop(l["to"]) ?: return@mapNotNull null
                val geo = geometry(l)
                if (mode == "WALK") {
                    Segment.Walk(
                        route = null,
                        duration = (l["duration"].num ?: 0.0).seconds,
                        from = from.latLng,
                        to = to.latLng,
                        geometry = geo.ifEmpty { listOf(from.latLng, to.latLng) },
                        distanceMeters = l["distance"].num ?: 0.0,
                    )
                } else {
                    Segment.TransitRide(
                        line = l["routeShortName"].str?.ifBlank { null } ?: l["displayName"].str ?: mode,
                        vehicle = vehicleFor(mode) ?: return@mapNotNull null,
                        headsign = l["headsign"].str.orEmpty(),
                        board = from,
                        alight = to,
                        departAt = Instant.parse(l["startTime"].str ?: return@mapNotNull null),
                        arriveAt = Instant.parse(l["endTime"].str ?: return@mapNotNull null),
                        intermediateStops = l["intermediateStops"].arr.mapNotNull { s -> stop(s) },
                        geometry = geo,
                        realTime = l["realTime"].bool ?: false,
                    )
                }
            }
            TransitItinerary(
                departAt = Instant.parse(o["startTime"].str ?: return@mapNotNull null),
                arriveAt = Instant.parse(o["endTime"].str ?: return@mapNotNull null),
                segments = segments,
            )
        }
    }
}

/** TfL in London (same engine as TfL's own planner), Transitous elsewhere or if TfL fails. */
class CompositeTransitProvider(
    private val london: TransitProvider?,
    private val elsewhere: TransitProvider,
) : TransitProvider {
    override suspend fun plan(from: LatLng, to: LatLng, departAt: Instant): List<TransitItinerary> {
        val tflProvider = london
        if (tflProvider != null && tflProvider.covers(from, to)) {
            val tfl = runCatching { tflProvider.plan(from, to, departAt) }
            tfl.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return elsewhere.plan(from, to, departAt)
    }
}
