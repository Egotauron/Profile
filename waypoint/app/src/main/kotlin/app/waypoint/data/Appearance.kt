package app.waypoint.data

import kotlinx.serialization.Serializable

/** Light or dark: the phone's setting, or always one. */
@Serializable
enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

/** The app's accent colours: the instruction banner, the ETA, buttons and highlights. */
@Serializable
enum class AccentPalette(val label: String) { PINE("Pine"), OCEAN("Ocean"), SUNSET("Sunset"), BERRY("Berry"), GRAPHITE("Graphite") }

/** The colour of the route you're driving (and of drive legs on the trip map). */
@Serializable
enum class RouteColour(val label: String, val argb: Long) {
    BLUE("Blue", 0xFF3583DD),
    GREEN("Green", 0xFF1E8E3E),
    PURPLE("Purple", 0xFF8E3BB5),
    ORANGE("Orange", 0xFFE8710A),
    RED("Red", 0xFFD93025),
}

/** OpenFreeMap's daytime map styles (night always uses Waypoint's readable dark style). */
@Serializable
enum class DayMap(val label: String, val url: String) {
    LIBERTY("Detailed", "https://tiles.openfreemap.org/styles/liberty"),
    BRIGHT("Bright", "https://tiles.openfreemap.org/styles/bright"),
    POSITRON("Minimal", "https://tiles.openfreemap.org/styles/positron"),
}
