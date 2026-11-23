package app.waypoint.trip

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    private const val EARTH_RADIUS_M = 6_371_008.8

    /** Great-circle distance in metres. */
    fun distanceMeters(a: LatLng, b: LatLng): Double {
        val dLat = (b.lat - a.lat).toRadians()
        val dLng = (b.lng - a.lng).toRadians()
        val h = sin(dLat / 2).pow(2) + cos(a.lat.toRadians()) * cos(b.lat.toRadians()) * sin(dLng / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(h)))
    }

    fun lengthMeters(line: List<LatLng>): Double =
        line.zipWithNext().sumOf { (a, b) -> distanceMeters(a, b) }

    /** Even-odd point-in-polygon test. [polygon] may or may not repeat its first point. */
    fun contains(polygon: List<LatLng>, p: LatLng): Boolean {
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[j]
            if ((a.lat > p.lat) != (b.lat > p.lat) &&
                p.lng < (b.lng - a.lng) * (p.lat - a.lat) / (b.lat - a.lat) + a.lng
            ) inside = !inside
            j = i
        }
        return inside
    }

    /** Points every ~[stepMeters] along [line], always including both ends. */
    fun resample(line: List<LatLng>, stepMeters: Double): List<LatLng> {
        if (line.size < 2) return line
        val out = mutableListOf(line.first())
        var carried = 0.0
        for ((a, b) in line.zipWithNext()) {
            val d = distanceMeters(a, b)
            if (d == 0.0) continue
            var t = stepMeters - carried
            while (t < d) {
                val f = t / d
                out += LatLng(a.lat + (b.lat - a.lat) * f, a.lng + (b.lng - a.lng) * f)
                t += stepMeters
            }
            carried = (carried + d) % stepMeters
        }
        if (out.last() != line.last()) out += line.last()
        return out
    }

    /** True when any part of [line] lies inside [polygon] (sampled every 50 m). */
    fun lineEnters(line: List<LatLng>, polygon: List<LatLng>): Boolean =
        resample(line, 50.0).any { contains(polygon, it) }

    /** Share (0..1) of [line]'s length inside [polygon], sampled. */
    fun fractionInside(line: List<LatLng>, polygon: List<LatLng>): Double {
        val samples = resample(line, maxOf(25.0, lengthMeters(line) / 200.0))
        if (samples.isEmpty()) return 0.0
        return samples.count { contains(polygon, it) }.toDouble() / samples.size
    }

    /** Walking estimate used only to rank options before asking the router. */
    fun roughWalkSeconds(a: LatLng, b: LatLng): Double = distanceMeters(a, b) * 1.3 / 1.35

    private fun Double.toRadians() = this * PI / 180.0
}

/** Encoded polyline (precision 6 = Valhalla / Ferrostar). */
object Polyline {
    fun decode(encoded: String, precision: Int = 6): List<LatLng> {
        val factor = 10.0.pow(precision)
        val out = ArrayList<LatLng>(encoded.length / 4)
        var index = 0
        var lat = 0L
        var lng = 0L
        while (index < encoded.length) {
            for (which in 0..1) {
                var shift = 0
                var result = 0L
                while (true) {
                    val b = encoded[index++].code - 63
                    result = result or ((b and 0x1f).toLong() shl shift)
                    shift += 5
                    if (b < 0x20) break
                }
                val delta = if (result and 1L != 0L) (result shr 1).inv() else result shr 1
                if (which == 0) lat += delta else lng += delta
            }
            out += LatLng(lat / factor, lng / factor)
        }
        return out
    }

    fun encode(points: List<LatLng>, precision: Int = 6): String {
        val factor = 10.0.pow(precision)
        val sb = StringBuilder()
        var prevLat = 0L
        var prevLng = 0L
        for (p in points) {
            val lat = (p.lat * factor).roundToLong()
            val lng = (p.lng * factor).roundToLong()
            encodeValue(lat - prevLat, sb)
            encodeValue(lng - prevLng, sb)
            prevLat = lat
            prevLng = lng
        }
        return sb.toString()
    }

    private fun encodeValue(value: Long, sb: StringBuilder) {
        var v = if (value < 0) (value shl 1).inv() else value shl 1
        while (v >= 0x20) {
            sb.append(((0x20 or (v and 0x1f).toInt()) + 63).toChar())
            v = v shr 5
        }
        sb.append((v.toInt() + 63).toChar())
    }
}

internal fun Double.nearlyEquals(other: Double, eps: Double = 1e-9) = abs(this - other) < eps
