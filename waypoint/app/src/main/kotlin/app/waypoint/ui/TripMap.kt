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
import org.maplibre.compose.map.OrnamentOptions
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

/** False in screenshot tests, where MapLibre's native renderer isn't available. */
val LocalMapRendering = androidx.compose.runtime.staticCompositionLocalOf { true }

object MapStyles {
    // OpenFreeMap: no key, no account (spec §2.1).
    const val DAY = "https://tiles.openfreemap.org/styles/liberty"
    const val NIGHT = "https://tiles.openfreemap.org/styles/dark"
}

private enum class LineKind { DRIVE, WALK, CYCLE, RIDE }

/**
 * Map preview of a trip: each leg drawn in its mode's colour, stops numbered in your order. "© OpenStreetMap contributors" stays visible (licence).
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
) {
    if (!LocalMapRendering.current) {
        androidx.compose.foundation.layout.Box(
            modifier.then(androidx.compose.ui.Modifier.background(if (dark) Color(0xFF1E2A26) else Color(0xFFDCE6E0))),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) { androidx.compose.material3.Text("map", color = Color(0xFF7A8A83)) }
        return
    }
    val camera = rememberCameraState(CameraPosition(target = Position(-0.1276, 51.5072), zoom = 11.0))
    val lines = remember(plan, focusLeg) { lineCollections(plan, focusLeg) }
    val stops = remember(trip, start) { stopsGeoJson(trip, start) }
    val pts = remember(plan, trip, start, focusLeg) { framePoints(plan, trip, start, focusLeg) }

    LaunchedEffect(pts) {
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
        baseStyle = BaseStyle.Uri(if (dark) MapStyles.NIGHT else MapStyles.DAY),
        cameraState = camera,
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
        for (kind in LineKind.entries) {
            val source = rememberGeoJsonSource(GeoJsonData.JsonString(lines.getValue(kind)))
            val color = when (kind) {
                LineKind.DRIVE -> Palette.Drive
                LineKind.WALK -> Palette.Walk
                LineKind.CYCLE -> Palette.Cycle
                LineKind.RIDE -> Palette.Transit
            }
            LineLayer(
                id = "casing-$kind", source = source,
                color = const(if (dark) Color(0xFF0D1412) else Color.White), width = const(9.dp),
                cap = const(LineCap.Round), join = const(LineJoin.Round),
            )
            LineLayer(
                id = "line-$kind", source = source,
                color = const(color), width = const(if (kind == LineKind.WALK) 4.dp else 5.5.dp),
                dasharray = if (kind == LineKind.WALK) const(listOf(0.1, 1.8)) else org.maplibre.compose.expressions.dsl.nil(),
                cap = const(LineCap.Round), join = const(LineJoin.Round),
            )
        }
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

private fun lineCollections(plan: Plan?, focusLeg: Int?): Map<LineKind, String> {
    val byKind = LineKind.entries.associateWith { mutableListOf<List<LatLng>>() }
    plan?.legs?.forEachIndexed { i, leg ->
        if (focusLeg != null && i != focusLeg) return@forEachIndexed
        for (s in leg.segments) when (s) {
            is Segment.Drive -> byKind.getValue(LineKind.DRIVE) += s.route.geometry
            is Segment.Walk -> byKind.getValue(LineKind.WALK) += s.geometry
            is Segment.Cycle -> byKind.getValue(LineKind.CYCLE) += s.route.geometry
            is Segment.TransitRide -> byKind.getValue(LineKind.RIDE) += s.geometry.ifEmpty { listOf(s.board.latLng, s.alight.latLng) }
            else -> Unit
        }
    }
    return byKind.mapValues { (_, list) -> multiLine(list) }
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

private fun stopsGeoJson(trip: Trip?, start: LatLng?): String = collection(
    buildList {
        if (start != null) add(pointFeature(start, mapOf("n" to "●")))
        trip?.stops?.forEachIndexed { i, s -> add(pointFeature(s.place.latLng, mapOf("n" to "${i + 1}"))) }
    },
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
