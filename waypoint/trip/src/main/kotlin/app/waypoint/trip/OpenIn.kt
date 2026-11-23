package app.waypoint.trip

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * "Open in…" fallback (spec §2.2): sends the remaining stops to another app. Only our own
 * coordinates are sent; no Google API is used.
 */
object OpenIn {
    /** Google Maps URL: up to 9 waypoints + destination. */
    const val GOOGLE_MAX_WAYPOINTS = 9

    data class Target(val latLng: LatLng, val name: String)

    fun googleMaps(origin: LatLng?, remaining: List<Target>, mode: TravelMode): String {
        require(remaining.isNotEmpty())
        val stops = remaining.take(GOOGLE_MAX_WAYPOINTS + 1)
        val destination = stops.last()
        val waypoints = stops.dropLast(1)
        val travelMode = when (mode) {
            TravelMode.DRIVE -> "driving"
            TravelMode.WALK -> "walking"
            TravelMode.CYCLE, TravelMode.BIKE_SHARE -> "bicycling"
            TravelMode.TRANSIT -> "transit"
        }
        return buildString {
            append("https://www.google.com/maps/dir/?api=1")
            if (origin != null) append("&origin=").append(coord(origin))
            append("&destination=").append(coord(destination.latLng))
            if (waypoints.isNotEmpty()) {
                append("&waypoints=").append(waypoints.joinToString("%7C") { coord(it.latLng) })
            }
            append("&travelmode=").append(travelMode)
        }
    }

    /** Organic Maps routes to one place at a time: the next stop. */
    fun organicMaps(origin: LatLng?, next: Target, mode: TravelMode): String {
        val type = when (mode) {
            TravelMode.DRIVE -> "vehicle"
            TravelMode.WALK -> "pedestrian"
            TravelMode.CYCLE, TravelMode.BIKE_SHARE -> "bicycle"
            TravelMode.TRANSIT -> "transit"
        }
        return buildString {
            append("om://route?")
            if (origin != null) append("sll=").append(coord(origin)).append("&saddr=").append(encode("My location")).append('&')
            append("dll=").append(coord(next.latLng)).append("&daddr=").append(encode(next.name))
            append("&type=").append(type)
        }
    }

    /** OsmAnd routes to one place at a time: the next stop. */
    fun osmAnd(next: Target, mode: TravelMode): String {
        val profile = when (mode) {
            TravelMode.DRIVE -> "car"
            TravelMode.WALK, TravelMode.TRANSIT -> "pedestrian"
            TravelMode.CYCLE, TravelMode.BIKE_SHARE -> "bicycle"
        }
        return "osmand.api://navigate?dest_lat=${num(next.latLng.lat)}&dest_lon=${num(next.latLng.lng)}" +
            "&dest_name=${encode(next.name)}&profile=$profile&force=true"
    }

    /** "51.507400,-0.127800" with a dot decimal separator whatever the phone's locale. */
    fun coord(p: LatLng): String = "${num(p.lat)},${num(p.lng)}"

    fun num(v: Double): String {
        val micro = (abs(v) * 1_000_000).roundToLong()
        val sign = if (v < 0 && micro != 0L) "-" else ""
        return "$sign${micro / 1_000_000}.${(micro % 1_000_000).toString().padStart(6, '0')}"
    }

    fun encode(s: String): String = buildString {
        for (b in s.encodeToByteArray()) {
            val c = b.toInt() and 0xff
            if (c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code || c.toChar() in "-_.~") {
                append(c.toChar())
            } else {
                append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
            }
        }
    }
}
