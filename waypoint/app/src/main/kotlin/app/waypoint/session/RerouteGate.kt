package app.waypoint.session

/**
 * Decides when being off the route is real enough to fetch a new one: you're moving, and you've
 * been off it for [PERSIST_MS] (two or three fixes). Standing still, GPS drift can put you 35 m
 * "off route" for a moment; re-routing on that made the map jump and redraw every few seconds.
 *
 * Ferrostar asks on every off-route fix while it's free to re-route, so a gap of more than
 * [GAP_MS] between questions means you were back on the route in between.
 */
class RerouteGate {
    private var since = 0L
    private var lastAsked = Long.MIN_VALUE / 2

    /** [speed] in m/s as the GPS reports it, null when it doesn't. */
    fun shouldReroute(nowMs: Long, speed: Double?): Boolean {
        if (nowMs - lastAsked > GAP_MS) since = nowMs
        lastAsked = nowMs
        val moving = speed == null || speed >= LocationPredictor.STILL_MPS
        return moving && nowMs - since >= PERSIST_MS
    }

    companion object {
        const val PERSIST_MS = 1_000L
        const val GAP_MS = 1_500L
    }
}
