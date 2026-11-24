package app.waypoint.providers

import app.waypoint.trip.LatLng
import app.waypoint.trip.Place
import app.waypoint.trip.PlaceProvider
import java.net.URLEncoder
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient

/**
 * Place search with Photon (komoot's public instance by default; spec §2.1). Callers debounce
 * typing to about one request a second. Results near [near] rank first.
 */
class PhotonPlaceProvider(
    private val baseUrl: String = "https://photon.komoot.io",
    private val language: String = "en",
    private val client: OkHttpClient = defaultHttpClient(),
) : PlaceProvider {

    override suspend fun search(query: String, near: LatLng?, limit: Int): List<Place> {
        if (query.isBlank()) return emptyList()
        val hits = request(query, near, limit)
        // A house number goes to that number, even when only a neighbour is on the map.
        return HouseNumbers.resolve(query, hits) { q -> request(q, near, 5) }.take(limit)
    }

    private suspend fun request(query: String, near: LatLng?, limit: Int): List<AddressHit> {
        val url = buildString {
            append(baseUrl).append("/api/?q=").append(URLEncoder.encode(query.trim(), "UTF-8"))
            append("&limit=").append(limit)
            append("&lang=").append(language)
            if (near != null) append("&lat=").append(near.lat).append("&lon=").append(near.lng)
        }
        return parseHits(client.getJson(url, "Photon search"))
    }

    override suspend fun reverse(point: LatLng): Place? {
        val url = "$baseUrl/reverse?lat=${point.lat}&lon=${point.lng}&lang=$language"
        return parse(client.getJson(url, "Photon reverse")).firstOrNull()?.copy(latLng = point)
    }

    companion object {
        fun parse(root: JsonElement): List<Place> = parseHits(root).map { it.place }

        fun parseHits(root: JsonElement): List<AddressHit> = root.obj?.get("features").arr.mapNotNull { f ->
            val props = f.obj?.get("properties").obj ?: return@mapNotNull null
            val coords = f.obj?.get("geometry").obj?.get("coordinates").arr
            val lng = coords.getOrNull(0).num ?: return@mapNotNull null
            val lat = coords.getOrNull(1).num ?: return@mapNotNull null
            val street = listOfNotNull(props["housenumber"].str, props["street"].str).joinToString(" ").ifBlank { null }
            val name = props["name"].str
            val label = name ?: street ?: props["city"].str ?: return@mapNotNull null
            val area = listOfNotNull(
                if (name != null) street else null,
                props["district"].str ?: props["locality"].str,
                props["city"].str?.takeIf { it != label },
                props["postcode"].str,
            ).distinct()
            val isStreet = props["type"].str == "street" || props["osm_key"].str == "highway"
            AddressHit(
                place = Place(
                    osmId = props["osm_type"].str?.let { t -> props["osm_id"].str?.let { "$t$it" } },
                    latLng = LatLng(lat, lng),
                    label = label,
                    address = area.joinToString(", ").ifBlank { null },
                ),
                houseNumber = props["housenumber"].str,
                streetName = props["street"].str ?: if (isStreet) name else null,
                isStreet = isStreet,
                postcode = props["postcode"].str,
            )
        }
    }
}
