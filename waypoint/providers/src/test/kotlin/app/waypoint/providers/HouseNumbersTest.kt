package app.waypoint.providers

import app.waypoint.trip.LatLng
import app.waypoint.trip.Place
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** With what Photon really returns for King's Road (checked live): 406 is mapped, 406A isn't. */
class HouseNumbersTest {
    private fun house(n: String, street: String, lat: Double, lng: Double, postcode: String? = null) =
        AddressHit(Place(latLng = LatLng(lat, lng), label = "$n $street"), houseNumber = n, streetName = street, postcode = postcode)
    private val kingsRoad = AddressHit(Place(latLng = LatLng(51.4817753, -0.1812707), label = "King's Road"), streetName = "King's Road", isStreet = true, postcode = "SW10 0LD")
    private val no406 = house("406", "King's Road", 51.4835709, -0.1771719, "SW10 0DB")

    // "406A Kings Road": 406a and 406A on other streets, and the street.
    private val firstHits = listOf(
        house("406a", "Wimbledon Park Road", 51.4397757, -0.2116202),
        house("406A", "Caledonian Road", 51.5434629, -0.1172624),
        kingsRoad,
    )

    @Test
    fun aLetteredNumberGoesNextDoorWhenOnlyTheNumberIsMapped() = runTest {
        val asked = mutableListOf<String>()
        val places = HouseNumbers.resolve("406A Kings Road", firstHits) { q -> asked += q; if (q == "406 Kings Road") listOf(no406) else emptyList() }
        val top = places.first()
        assertEquals("406A King's Road", top.label)
        assertEquals(no406.place.latLng, top.latLng)
        assertEquals("next to no. 406 · SW10 0DB", top.address)
        assertEquals(listOf("406 Kings Road"), asked)
    }

    @Test
    fun anExactMatchIsUsedAsItIs() = runTest {
        val places = HouseNumbers.resolve("406 Kings Road", listOf(house("406", "Kingsland Road", 51.54, -0.07), no406)) { error("no lookups needed") }
        assertEquals(no406.place, places.first())
    }

    @Test
    fun otherwiseBetweenTheNeighbours() = runTest {
        val no404 = house("404", "King's Road", 51.4834, -0.1770)
        val no408 = house("408", "King's Road", 51.4838, -0.1774)
        val places = HouseNumbers.resolve("406 King's Rd", listOf(kingsRoad)) { q ->
            when (q) { "404 King's Rd" -> listOf(no404); "408 King's Rd" -> listOf(no408); else -> emptyList() }
        }
        assertEquals("406 King's Road", places.first().label)
        assertEquals(51.4836, places.first().latLng.lat, 1e-6)
        assertTrue(places.first().address!!.startsWith("between no. 404 and 408"))
    }

    @Test
    fun lastlyTheStreetSayingSo() = runTest {
        val places = HouseNumbers.resolve("406A Kings Road", firstHits) { emptyList() }
        assertEquals("406A King's Road", places.first().label)
        assertEquals(kingsRoad.place.latLng, places.first().latLng)
        assertTrue(places.first().address!!.startsWith("number not on the map"))
    }

    @Test
    fun queriesAreReadLikeAddresses() {
        val q = HouseNumbers.parse("Flat 2, 406a King's Rd, Chelsea")!!
        assertEquals(406, q.number)
        assertEquals("A", q.letter)
        assertEquals("King's Rd", q.street)
        assertTrue(HouseNumbers.sameStreet("King's Rd", "kings road"))
        assertNull(HouseNumbers.parse("Royal Albert Hall"))
        assertNull(HouseNumbers.parse("221"))
        assertEquals("10", HouseNumbers.parse("10 Downing Street")!!.houseNumber)
    }

    @Test
    fun ordinaryPlacesAreUntouched() = runTest {
        val hall = AddressHit(Place(latLng = LatLng(51.501, -0.177), label = "Royal Albert Hall"))
        assertEquals(listOf(hall.place), HouseNumbers.resolve("Royal Albert Hall", listOf(hall)) { error("no lookups") })
    }
}
