package app.waypoint.data

/**
 * Favourite categories: Home and Work (one place each, the map's first two quick buttons), a few
 * common ones, and any of your own.
 */
object FavouriteCategories {
    const val HOME = "Home"
    const val WORK = "Work"

    val BUILT_IN = listOf(HOME, WORK, "Family", "Friends", "Food & drink", "Shopping", "Gym & sport", "Health")

    /** Home and Work belong to one place each. */
    fun isUnique(category: String?): Boolean = category == HOME || category == WORK

    /** Trimmed, blank as none, and a built-in name in its own spelling ("home" is Home). */
    fun normalise(category: String?): String? {
        val c = category?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() } ?: return null
        return BUILT_IN.firstOrNull { it.equals(c, ignoreCase = true) } ?: c
    }

    /** The categories to offer: the built-in ones, then your own (A–Z; "climbing" and "Climbing" are one). */
    fun all(favourites: List<Favourite>): List<String> =
        BUILT_IN + favourites.mapNotNull { normalise(it.category) }.filter { it !in BUILT_IN }
            .distinctBy { it.lowercase() }.sortedBy { it.lowercase() }

    /** A typed category as one you already have, whatever its capitals; else as typed. */
    fun match(typed: String?, favourites: List<Favourite>): String? {
        val c = normalise(typed) ?: return null
        return all(favourites).firstOrNull { it.equals(c, ignoreCase = true) } ?: c
    }

    /** Favourites by category, in [all]'s order, then the ones without one (null). Each A–Z. */
    fun grouped(favourites: List<Favourite>): List<Pair<String?, List<Favourite>>> {
        val byCategory = favourites.groupBy { normalise(it.category)?.lowercase() }
        return (all(favourites).map { it as String? } + listOf(null))
            .mapNotNull { c -> byCategory[c?.lowercase()]?.let { list -> c to list.sortedBy { it.place.label.lowercase() } } }
    }

    fun home(favourites: List<Favourite>): Favourite? = favourites.firstOrNull { normalise(it.category) == HOME }
    fun work(favourites: List<Favourite>): Favourite? = favourites.firstOrNull { normalise(it.category) == WORK }

    /** The third quick button's favourite (not Home or Work, which have their own). */
    fun quick(favourites: List<Favourite>): Favourite? = favourites.firstOrNull { it.quick && !isUnique(normalise(it.category)) }
}
