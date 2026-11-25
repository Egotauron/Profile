package app.waypoint.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.waypoint.trip.LatLng
import app.waypoint.trip.Plan
import app.waypoint.trip.Segment
import app.waypoint.trip.TravelMode
import app.waypoint.trip.Trip
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.util.ClickResult
import org.maplibre.compose.map.OrnamentOptions
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

/** False in screenshot tests, where MapLibre's native renderer isn't available. */
val LocalMapRendering = androidx.compose.runtime.staticCompositionLocalOf { true }

object MapStyles {
    // OpenFreeMap: no key, no account (spec §2.1). Day style: Settings, Appearance (LocalDayMapStyle).
    const val DAY = "https://tiles.openfreemap.org/styles/liberty"
    /**
     * OpenFreeMap's "dark" style with readable roads: its streets are near-black on black, so
     * tools/night_style.py repaints roads, paths and street names in greys. Bundled with the app;
     * tiles, fonts and icons still come from OpenFreeMap.
     */
    const val NIGHT = "asset://styles/night.json"
}

internal enum class LineKind { DRIVE, WALK, CYCLE, BUS, TUBE, TRAIN, TRAM }

private fun LineKind.color(route: Color): Color = when (this) {
    LineKind.DRIVE -> route
    LineKind.WALK -> Palette.Walk
    LineKind.CYCLE -> Palette.Cycle
    LineKind.BUS -> Palette.Bus
    LineKind.TUBE -> Palette.Tube
    LineKind.TRAIN -> Palette.Train
    LineKind.TRAM -> Palette.Tram
}

private fun kindOf(v: app.waypoint.trip.TransitVehicle) = when (v) {
    app.waypoint.trip.TransitVehicle.BUS -> LineKind.BUS
    app.waypoint.trip.TransitVehicle.SUBWAY -> LineKind.TUBE
    app.waypoint.trip.TransitVehicle.TRAIN -> LineKind.TRAIN
    app.waypoint.trip.TransitVehicle.TRAM -> LineKind.TRAM
}

/**
 * Map preview of a trip: each leg drawn in its mode's colour (transit in its line colour), stops
 * numbered in your order. [fromLeg] skips legs already travelled; [focusLeg] is the leg the camera
 * frames. "© OpenStreetMap contributors" stays visible (licence).
 */
@Composable
fun TripMap(
    modifier: Modifier,
    trip: Trip?,
    plan: Plan?,
    start: LatLng?,
    dark: Boolean,
    user: LatLng? = null,
    focusLeg: Int? = null,
    bottomPadding: Int = 0,
    fromLeg: Int = 0,
    afterSegment: Int = -1,
    /** Long-press on the map (the builder drops a pin there). */
    onLongPress: ((LatLng) -> Unit)? = null,
    /** A dropped pin to show. */
    pin: LatLng? = null,
) {
    if (!LocalMapRendering.current) {
        androidx.compose.foundation.layout.Box(
            modifier.then(androidx.compose.ui.Modifier.background(if (dark) Color(0xFF1E2A26) else Color(0xFFDCE6E0))),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) { androidx.compose.material3.Text("map", color = Color(0xFF7A8A83)) }
        return
    }
    val camera = rememberCameraState(CameraPosition(target = Position(-0.1276, 51.5072), zoom = 11.0))
    // Nothing to frame (no stops yet): centre on you, once your location is known.
    val located = user != null
    val pts = remember(plan, trip, start, focusLeg, located) { framePoints(plan, trip, start, focusLeg).ifEmpty { listOfNotNull(user) } }

    // Re-framed when what's to show changes, or how much of the map is visible (the builder's sheet).
    LaunchedEffect(pts, bottomPadding) {
        if (pts.isEmpty()) return@LaunchedEffect
        if (pts.size == 1) {
            camera.animateTo(CameraPosition(target = Position(pts[0].lng, pts[0].lat), zoom = 14.0))
        } else {
            val box = BoundingBox(pts.minOf { it.lng }, pts.minOf { it.lat }, pts.maxOf { it.lng }, pts.maxOf { it.lat })
            camera.animateTo(boundingBox = box, padding = PaddingValues(start = 48.dp, top = 64.dp, end = 48.dp, bottom = (48 + bottomPadding).dp))
        }
    }

    MaplibreMap(
        modifier = modifier,
        baseStyle = BaseStyle.Uri(if (dark) MapStyles.NIGHT else LocalDayMapStyle.current),
        cameraState = camera,
        onMapLongClick = { position, _ ->
            onLongPress?.let { it(LatLng(position.latitude, position.longitude)); ClickResult.Consume } ?: ClickResult.Pass
        },
        options = MapOptions(
            ornamentOptions = OrnamentOptions(
                isLogoEnabled = false,
                isAttributionEnabled = true,
                isCompassEnabled = false,
                isScaleBarEnabled = false,
                padding = PaddingValues(bottom = bottomPadding.dp),
            ),
        ),
    ) {
        TripLayers(trip, plan, if (fromLeg == 0) start else null, dark, fromLeg = fromLeg, afterSegment = afterSegment, currentLeg = focusLeg)
        if (pin != null) {
            val dropped = rememberGeoJsonSource(GeoJsonData.JsonString(pointGeoJson(pin)))
            CircleLayer(id = "pin-halo", source = dropped, color = const(Palette.Warn), opacity = const(0.25f), radius = const(18.dp))
            CircleLayer(
                id = "pin", source = dropped, color = const(Palette.Warn), radius = const(9.dp),
                strokeColor = const(Color.White), strokeWidth = const(3.dp),
            )
        }
        if (user != null) {
            val me = rememberGeoJsonSource(GeoJsonData.JsonString(pointGeoJson(user)))
            CircleLayer(id = "me-halo", source = me, color = const(Palette.Drive), opacity = const(0.2f), radius = const(16.dp))
            CircleLayer(
                id = "me", source = me, color = const(Palette.Drive), radius = const(7.dp),
                strokeColor = const(Color.White), strokeWidth = const(2.5.dp),
            )
        }
    }
}

/**
 * The trip's lines and stops as map layers, for the trip map and for Ferrostar's navigation map.
 * Legs before [fromLeg] are left out; on [fromLeg] only the segments after [afterSegment] are drawn
 * (the one being guided is Ferrostar's own blue line). The current leg is drawn solid and later legs
 * fainter, so you always see where the whole trip goes.
 */
@Composable
@org.maplibre.compose.util.MaplibreComposable
fun TripLayers(
    trip: Trip?,
    plan: Plan?,
    start: LatLng?,
    dark: Boolean,
    fromLeg: Int = 0,
    afterSegment: Int = -1,
    currentLeg: Int? = null,
) {
    val routeColour = LocalRouteColour.current
    val tiers = remember(plan, fromLeg, afterSegment, currentLeg) { lineCollections(plan, fromLeg, afterSegment, currentLeg ?: fromLeg) }
    val stops = remember(trip, start, fromLeg) { stopsGeoJson(trip, start, fromLeg) }
    val transitStops = remember(plan, fromLeg, afterSegment) { transitStopsGeoJson(plan, fromLeg, afterSegment) }
    for ((tier, lines) in tiers) {
        val opacity = if (tier == 0) 1f else 0.45f
        for (kind in LineKind.entries) {
            val source = rememberGeoJsonSource(GeoJsonData.JsonString(lines.getValue(kind)))
            LineLayer(
                id = "casing-$tier-$kind", source = source, opacity = const(opacity),
                color = const(if (dark) Color(0xFF0D1412) else Color.White), width = const(9.dp),
                cap = const(LineCap.Round), join = const(LineJoin.Round),
            )
            LineLayer(
                id = "line-$tier-$kind", source = source, opacity = const(opacity),
                color = const(kind.color(routeColour)), width = const(if (kind == LineKind.WALK) 4.dp else 5.5.dp),
                dasharray = if (kind == LineKind.WALK) const(listOf(0.1, 1.8)) else org.maplibre.compose.expressions.dsl.nil(),
                cap = const(LineCap.Round), join = const(LineJoin.Round),
            )
        }
    }
    val transitSource = rememberGeoJsonSource(GeoJsonData.JsonString(transitStops))
    CircleLayer(
        id = "transit-stops", source = transitSource, color = const(Color.White), radius = const(5.dp),
        strokeColor = const(Palette.Ink), strokeWidth = const(2.dp),
    )
    val stopSource = rememberGeoJsonSource(GeoJsonData.JsonString(stops))
    CircleLayer(
        id = "stops", source = stopSource, color = const(Palette.Amber), radius = const(12.dp),
        strokeColor = const(Palette.Pine), strokeWidth = const(2.5.dp),
    )
    SymbolLayer(
        id = "stop-numbers", source = stopSource,
        textField = feature["n"].asString().cast(),
        textFont = const(listOf("Noto Sans Bold")),
        textSize = const(13.sp), textColor = const(Palette.Pine), textAllowOverlap = const(true),
    )
}

/** Tier 0 = the current leg (solid), tier 1 = later legs (faint). */
internal fun lineCollections(plan: Plan?, fromLeg: Int, afterSegment: Int, currentLeg: Int): Map<Int, Map<LineKind, String>> {
    val tiers = mapOf(0 to LineKind.entries.associateWith { mutableListOf<List<LatLng>>() }, 1 to LineKind.entries.associateWith { mutableListOf<List<LatLng>>() })
    plan?.legs?.forEachIndexed { i, leg ->
        if (i < fromLeg) return@forEachIndexed
        val byKind = tiers.getValue(if (i <= currentLeg) 0 else 1)
        leg.segments.forEachIndexed { k, s ->
            if (i == fromLeg && k <= afterSegment) return@forEachIndexed
            when (s) {
                is Segment.Drive -> byKind.getValue(LineKind.DRIVE) += s.route.geometry
                is Segment.Walk -> byKind.getValue(LineKind.WALK) += s.geometry
                is Segment.Cycle -> byKind.getValue(LineKind.CYCLE) += s.route.geometry
                is Segment.TransitRide -> byKind.getValue(kindOf(s.vehicle)) += s.geometry.ifEmpty { listOf(s.board.latLng, s.alight.latLng) }
                else -> Unit
            }
        }
    }
    return tiers.mapValues { (_, byKind) -> byKind.mapValues { (_, list) -> multiLine(list) } }
}

private fun multiLine(lines: List<List<LatLng>>): String = buildJsonObject {
    put("type", "FeatureCollection")
    put("features", JsonArray(lines.filter { it.size >= 2 }.map { line ->
        buildJsonObject {
            put("type", "Feature")
            put("properties", buildJsonObject {})
            put("geometry", buildJsonObject {
                put("type", "LineString")
                put("coordinates", buildJsonArray { line.forEach { add(buildJsonArray { add(JsonPrimitive(it.lng)); add(JsonPrimitive(it.lat)) }) } })
            })
        }
    }))
}.toString()

private fun pointFeature(p: LatLng, props: Map<String, String>) = buildJsonObject {
    put("type", "Feature")
    put("properties", buildJsonObject { props.forEach { (k, v) -> put(k, v) } })
    put("geometry", buildJsonObject {
        put("type", "Point")
        put("coordinates", buildJsonArray { add(JsonPrimitive(p.lng)); add(JsonPrimitive(p.lat)) })
    })
}

private fun collection(features: List<kotlinx.serialization.json.JsonObject>) = buildJsonObject {
    put("type", "FeatureCollection")
    put("features", JsonArray(features))
}.toString()

private fun stopsGeoJson(trip: Trip?, start: LatLng?, fromLeg: Int): String = collection(
    buildList {
        if (start != null) add(pointFeature(start, mapOf("n" to "●")))
        trip?.stops?.forEachIndexed { i, s -> if (i >= fromLeg - 1) add(pointFeature(s.place.latLng, mapOf("n" to "${i + 1}"))) }
    },
)

/** Where you board and get off buses and trains on the drawn part of the trip. */
internal fun transitStopsGeoJson(plan: Plan?, fromLeg: Int, afterSegment: Int): String = collection(
    plan?.legs.orEmpty().flatMapIndexed { i, leg ->
        if (i < fromLeg) emptyList()
        else leg.segments.filterIndexed { k, _ -> !(i == fromLeg && k <= afterSegment) }
            .filterIsInstance<Segment.TransitRide>()
            .flatMap { listOf(it.board.latLng, it.alight.latLng) }
    }.map { pointFeature(it, emptyMap()) },
)

private fun pointGeoJson(p: LatLng): String = collection(listOf(pointFeature(p, emptyMap())))

private fun framePoints(plan: Plan?, trip: Trip?, start: LatLng?, focusLeg: Int?): List<LatLng> {
    if (plan != null) {
        val legs = if (focusLeg != null) listOfNotNull(plan.legs.getOrNull(focusLeg)) else plan.legs
        val pts = legs.flatMap { leg ->
            leg.segments.flatMap { s ->
                when (s) {
                    is Segment.Drive -> s.route.geometry
                    is Segment.Walk -> s.geometry
                    is Segment.Cycle -> s.route.geometry
                    is Segment.TransitRide -> listOf(s.board.latLng, s.alight.latLng)
                    else -> emptyList()
                }
            }
        }
        if (pts.isNotEmpty()) return pts
    }
    return listOfNotNull(start) + (trip?.stops?.map { it.place.latLng } ?: emptyList())
}
