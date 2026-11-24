package app.waypoint.providers

import app.waypoint.trip.LatLng
import app.waypoint.trip.Place

/** One place search result, with the address parts house numbers are matched on. */
data class AddressHit(
    val place: Place,
    val houseNumber: String? = null,
    /** The street the place is on, or the street itself for a street result. */
    val streetName: String? = null,
    val isStreet: Boolean = false,
    val postcode: String? = null,
)

/**
 * Searches for a numbered address ("406A Kings Road") go to that number, not just the street.
 *
 * OpenStreetMap has most house numbers but few flat or unit letters: 406 King's Road is mapped,
 * 406A isn't, so the search finds the street (and 406A on other streets). In that case the
 * number is placed, in order of preference:
 * 1. at the same number without the letter on the same street (next door);
 * 2. between the neighbouring numbers on the same side (404 and 408);
 * 3. on the street, saying the number isn't on the map.
 * The result is labelled with the address you typed and says where it was placed.
 */
object HouseNumbers {
    data class Query(val number: Int, val letter: String, val street: String, val rest: String) {
        val houseNumber: String get() = "$number$letter"
    }

    private val PATTERN = Regex(
        """^\s*(?:(?:flat|apt|apartment|unit)\s+\w+\s*,?\s*)?(\d{1,5})\s*([a-z])?(?=[\s,])[\s,]+(.+)$""",
        RegexOption.IGNORE_CASE,
    )

    /** "Flat 2, 406A King's Rd, Chelsea" → 406, "A", "King's Rd", "King's Rd, Chelsea". */
    fun parse(query: String): Query? {
        val m = PATTERN.matchEntire(query.trim()) ?: return null
        val rest = m.groupValues[3].trim()
        val street = rest.substringBefore(',').trim()
        if (street.count { it.isLetter() } < 3) return null
        return Query(m.groupValues[1].toInt(), m.groupValues[2].uppercase(), street, rest)
    }

    private val ABBREVIATIONS = mapOf(
        "rd" to "road", "st" to "street", "ave" to "avenue", "av" to "avenue", "ln" to "lane", "pl" to "place",
        "sq" to "square", "gdns" to "gardens", "cres" to "crescent", "ct" to "court", "dr" to "drive",
        "tce" to "terrace", "terr" to "terrace", "gr" to "grove", "pk" to "park", "hwy" to "highway",
    )

    /** "King's Rd" and "kings road" are the same street. */
    fun sameStreet(a: String, b: String): Boolean = normalise(a) == normalise(b)

    private fun normalise(s: String): String = s.lowercase()
        .replace("'", "").replace("’", "")
        .replace(Regex("[^a-z0-9]+"), " ").trim()
        .split(" ").filter { it.isNotEmpty() }.joinToString(" ") { ABBREVIATIONS[it] ?: it }

    /**
     * [hits] are the results for [query]; [search] looks up another query (a neighbouring number).
     * Returns the places to show, the resolved address first.
     */
    suspend fun resolve(query: String, hits: List<AddressHit>, search: suspend (String) -> List<AddressHit>): List<Place> {
        val q = parse(query) ?: return hits.map { it.place }
        fun onStreet(h: AddressHit) = h.streetName?.let { sameStreet(it, q.street) } == true
        fun numbered(h: AddressHit, n: String) = h.houseNumber.equals(n, ignoreCase = true) && onStreet(h)

        val exact = hits.firstOrNull { numbered(it, q.houseNumber) }
        if (exact != null) return listOf(exact.place) + hits.filter { it !== exact }.map { it.place }

        val street = hits.firstOrNull { it.isStreet && onStreet(it) }
        val others = hits.map { it.place }
        val streetLabel = street?.streetName ?: hits.firstNotNullOfOrNull { h -> h.streetName?.takeIf { onStreet(h) } } ?: q.street

        fun placed(at: LatLng, where: String, postcode: String?) = Place(
            osmId = null,
            latLng = at,
            label = "${q.houseNumber} $streetLabel",
            address = listOfNotNull(where, postcode).joinToString(" · "),
        )

        // 1. Same number without the letter: next door.
        if (q.letter.isNotEmpty()) {
            search("${q.number} ${q.rest}").firstOrNull { numbered(it, "${q.number}") }?.let { base ->
                return listOf(placed(base.place.latLng, "next to no. ${q.number}", base.postcode)) + others
            }
        }
        // 2. Between the neighbours on the same side of the road.
        val before = if (q.number > 2) search("${q.number - 2} ${q.rest}").firstOrNull { numbered(it, "${q.number - 2}") } else null
        val after = search("${q.number + 2} ${q.rest}").firstOrNull { numbered(it, "${q.number + 2}") }
        if (before != null && after != null) {
            val mid = LatLng((before.place.latLng.lat + after.place.latLng.lat) / 2, (before.place.latLng.lng + after.place.latLng.lng) / 2)
            return listOf(placed(mid, "between no. ${q.number - 2} and ${q.number + 2}", before.postcode ?: after.postcode)) + others
        }
        (before ?: after)?.let { n ->
            return listOf(placed(n.place.latLng, "next to no. ${n.houseNumber}", n.postcode)) + others
        }
        // 3. The street, saying so.
        street?.let { s ->
            return listOf(placed(s.place.latLng, "number not on the map: somewhere on this street", s.postcode)) + others.filter { it != s.place }
        }
        return others
    }
}
