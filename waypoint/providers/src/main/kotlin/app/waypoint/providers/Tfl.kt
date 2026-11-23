package app.waypoint.providers

import app.waypoint.trip.BikeShareProvider
import app.waypoint.trip.Dock
import app.waypoint.trip.Geo
import app.waypoint.trip.LatLng
import app.waypoint.trip.London
import app.waypoint.trip.ProviderException
import app.waypoint.trip.Segment
import app.waypoint.trip.TransitItinerary
import app.waypoint.trip.TransitProvider
import app.waypoint.trip.TransitStop
import app.waypoint.trip.TransitVehicle
import java.util.Locale
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient

/**
 * TfL Unified API (spec §2.1): Journey Planner in London, live arrivals for the transit companion,
 * and Santander Cycles docks (BikePoint). A free app key raises the anonymous 50 requests/minute
 * limit; data "Powered by TfL Open Data".
 */
class TflClient(
    private val appKey: String? = null,
    private val baseUrl: String = "https://api.tfl.gov.uk",
    private val client: OkHttpClient = defaultHttpClient(),
) {
    private fun withKey(url: String): String =
        if (appKey.isNullOrBlank()) url else url + (if ('?' in url) "&" else "?") + "app_key=" + appKey

    suspend fun journey(from: LatLng, to: LatLng, departAt: Instant): List<TransitItinerary> {
        val local = departAt.toLocalDateTime(London.timeZone)
        val date = String.format(Locale.ROOT, "%04d%02d%02d", local.year, local.month.ordinal + 1, local.day)
        val time = String.format(Locale.ROOT, "%02d%02d", local.hour, local.minute)
        val url = "$baseUrl/Journey/JourneyResults/${c(from)}/to/${c(to)}" +
            "?mode=$MODES&date=$date&time=$time&timeIs=Departing&journeyPreference=leasttime&alternativeWalking=false"
        return parseJourneys(client.getJson(withKey(url), "TfL Journey Planner"))
    }

    data class Arrival(val line: String, val destination: String, val expected: Instant, val platform: String?)

    /** Live arrivals at a stop (soonest first), for the transit companion. */
    suspend fun arrivals(stopId: String, line: String? = null): List<Arrival> {
        val root = client.getJson(withKey("$baseUrl/StopPoint/$stopId/Arrivals"), "TfL arrivals")
        return root.arr.mapNotNull { a ->
            val o = a.obj ?: return@mapNotNull null
            Arrival(
                line = o["lineName"].str ?: return@mapNotNull null,
                destination = o["destinationName"].str.orEmpty().removeSuffix(" Underground Station"),
                expected = o["expectedArrival"].str?.let { Instant.parse(it) } ?: return@mapNotNull null,
                platform = o["platformName"].str,
            )
        }.filter { line == null || it.line.equals(line, ignoreCase = true) }.sortedBy { it.expected }
    }

    suspend fun bikePoints(): List<Dock> = parseBikePoints(client.getJson(withKey("$baseUrl/BikePoint"), "TfL BikePoint"))

    companion object {
        const val MODES = "bus,tube,overground,dlr,elizabeth-line,national-rail,tram,walking"

        private fun c(p: LatLng) = "${p.lat},${p.lng}"

        /** Tube & DLR → Tube; Overground, Elizabeth line and National Rail → Train. */
        fun vehicleFor(modeId: String): TransitVehicle? = when (modeId) {
            "bus", "coach" -> TransitVehicle.BUS
            "tube", "dlr" -> TransitVehicle.SUBWAY
            "overground", "elizabeth-line", "national-rail", "international-rail" -> TransitVehicle.TRAIN
            "tram", "cable-car" -> TransitVehicle.TRAM
            else -> null
        }

        /** TfL times are London local time without an offset. */
        fun parseLocal(s: String): Instant = LocalDateTime.parse(s).toInstant(London.timeZone)

        private fun point(o: JsonElement?): TransitStop? {
            val p = o.obj ?: return null
            return TransitStop(
                name = p["commonName"].str?.removeSuffix(" Underground Station") ?: "",
                latLng = LatLng(p["lat"].num ?: return null, p["lon"].num ?: return null),
                id = p["individualStopId"].str ?: p["naptanId"].str,
            )
        }

        private fun lineString(s: String?): List<LatLng> = runCatching {
            lenientJson.parseToJsonElement(s ?: return emptyList()).arr.mapNotNull { pt ->
                val a = pt.arr
                LatLng(a.getOrNull(0).num ?: return@mapNotNull null, a.getOrNull(1).num ?: return@mapNotNull null)
            }
        }.getOrDefault(emptyList())

        fun parseJourneys(root: JsonElement): List<TransitItinerary> = root.obj?.get("journeys").arr.mapNotNull { j ->
            val jo = j.obj ?: return@mapNotNull null
            val segments = jo["legs"].arr.map { legEl ->
                val l = legEl.obj ?: throw ProviderException("TfL: bad leg")
                val modeId = l["mode"].obj?.get("id").str ?: "walking"
                val dep = parseLocal(l["departureTime"].str!!)
                val arr = parseLocal(l["arrivalTime"].str!!)
                val from = point(l["departurePoint"])
                val to = point(l["arrivalPoint"])
                val path = lineString(l["path"].obj?.get("lineString").str)
                val vehicle = vehicleFor(modeId)
                if (vehicle == null || from == null || to == null) {
                    // Walking (and anything we don't model) is shown as a walk.
                    val a = from?.latLng ?: path.firstOrNull() ?: LatLng(0.0, 0.0)
                    val b = to?.latLng ?: path.lastOrNull() ?: a
                    Segment.Walk(
                        route = null,
                        duration = (l["duration"].num ?: 0.0).minutes,
                        from = a,
                        to = b,
                        geometry = path.ifEmpty { listOf(a, b) },
                        distanceMeters = l["distance"].num ?: Geo.lengthMeters(path),
                    )
                } else {
                    val option = l["routeOptions"].arr.firstOrNull().obj
                    val headsign = option?.get("directions").arr.firstOrNull().str.orEmpty()
                        .removeSuffix(" Underground Station")
                    Segment.TransitRide(
                        line = option?.get("name").str?.ifBlank { null } ?: l["instruction"].obj?.get("summary").str.orEmpty(),
                        vehicle = vehicle,
                        headsign = headsign,
                        board = from,
                        alight = to,
                        departAt = dep,
                        arriveAt = arr,
                        intermediateStops = emptyList(), // TfL gives names without positions
                        geometry = path,
                        realTime = false,
                    )
                }
            }
            if (segments.isEmpty()) return@mapNotNull null
            TransitItinerary(
                departAt = parseLocal(jo["startDateTime"].str ?: return@mapNotNull null),
                arriveAt = parseLocal(jo["arrivalDateTime"].str ?: return@mapNotNull null),
                segments = segments,
            )
        }

        fun parseBikePoints(root: JsonElement): List<Dock> = root.arr.mapNotNull { p ->
            val o = p.obj ?: return@mapNotNull null
            val props = o["additionalProperties"].arr.associate { it.obj?.get("key").str to it.obj?.get("value").str }
            if (props["Installed"] == "false" || props["Locked"] == "true") return@mapNotNull null
            Dock(
                id = o["id"].str ?: return@mapNotNull null,
                name = o["commonName"].str.orEmpty().replace(" ,", ","), // TfL writes "River Street , Clerkenwell"
                latLng = LatLng(o["lat"].num ?: return@mapNotNull null, o["lon"].num ?: return@mapNotNull null),
                bikesAvailable = props["NbBikes"]?.toIntOrNull() ?: 0,
                spacesAvailable = props["NbEmptyDocks"]?.toIntOrNull() ?: 0,
                system = "Santander Cycles",
            )
        }
    }
}

class TflTransitProvider(private val tfl: TflClient) : TransitProvider {
    override suspend fun plan(from: LatLng, to: LatLng, departAt: Instant): List<TransitItinerary> =
        tfl.journey(from, to, departAt)

    override fun covers(from: LatLng, to: LatLng): Boolean = London.inGreaterLondon(from) && London.inGreaterLondon(to)
}

/** Santander Cycles docks; the full list is cached for a minute. */
class TflBikePointProvider(private val tfl: TflClient, private val cacheFor: kotlin.time.Duration = 60.seconds) : BikeShareProvider {
    private val mutex = Mutex()
    private var cached: Pair<kotlin.time.TimeMark, List<Dock>>? = null

    override suspend fun docksNear(point: LatLng, radiusMeters: Double): List<Dock> {
        if (!London.inGreaterLondon(point)) return emptyList()
        val all = mutex.withLock {
            val c = cached
            if (c != null && c.first.elapsedNow() < cacheFor) c.second
            else tfl.bikePoints().also { cached = kotlin.time.TimeSource.Monotonic.markNow() to it }
        }
        return all.filter { Geo.distanceMeters(it.latLng, point) <= radiusMeters }
    }
}
