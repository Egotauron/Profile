package app.waypoint

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.waypoint.data.Favourite
import app.waypoint.data.FavouriteCategories
import app.waypoint.data.Repository
import app.waypoint.data.WaypointDatabase
import app.waypoint.trip.LatLng
import app.waypoint.trip.Place
import app.waypoint.ui.favouritesGeoJson
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Favourites with categories: Home, Work, your own, and the third quick button. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class FavouritesTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun place(name: String, lat: Double) = Place("osm:$name", LatLng(lat, -0.1), name, "$name Road")
    private fun fav(name: String, category: String?, quick: Boolean = false) = Favourite("osm:$name", place(name, 51.5), null, category, quick)

    @Test
    fun categoriesAreTidiedAndListedBuiltInFirst() {
        assertEquals("Home", FavouriteCategories.normalise("  home "))
        assertEquals("Food & drink", FavouriteCategories.normalise("FOOD & DRINK"))
        assertEquals("Climbing", FavouriteCategories.normalise(" Climbing  "))
        assertNull(FavouriteCategories.normalise("   "))
        val favourites = listOf(fav("Wall", "climbing"), fav("Crag", "Climbing"), fav("Pub", "Food & drink"), fav("Mum", "Home"), fav("Spot", null))
        // Your own categories after the built-in ones; "climbing" and "Climbing" are one.
        assertEquals(FavouriteCategories.BUILT_IN + "climbing", FavouriteCategories.all(favourites))
        assertEquals("climbing", FavouriteCategories.match(" CLIMBING ", favourites))
        assertEquals("Bouldering", FavouriteCategories.match("Bouldering", favourites))
        val grouped = FavouriteCategories.grouped(favourites)
        assertEquals(listOf("Home", "Food & drink", "climbing", null), grouped.map { it.first })
        assertEquals(listOf("Crag", "Wall"), grouped[2].second.map { it.place.label })
    }

    @Test
    fun theQuickButtonsFindHomeWorkAndTheThird() {
        val favourites = listOf(fav("Flat", "Home"), fav("Office", "Work"), fav("Gym", "Gym & sport", quick = true), fav("Mum", "Family"))
        assertEquals("Flat", FavouriteCategories.home(favourites)?.place?.label)
        assertEquals("Office", FavouriteCategories.work(favourites)?.place?.label)
        assertEquals("Gym", FavouriteCategories.quick(favourites)?.place?.label)
        // Home has its own button: never the third.
        assertNull(FavouriteCategories.quick(listOf(fav("Flat", "Home", quick = true))))
    }

    @Test
    fun homeMovesRatherThanDoublesAndResavingKeepsTheCategory() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(app, WaypointDatabase::class.java).allowMainThreadQueries().build()
        val repo = Repository(db.dao())
        repo.saveFavourite(place("Old flat", 51.4), category = "home")
        repo.saveFavourite(place("New flat", 51.6), category = "Home")
        var all = repo.favourites.first()
        assertEquals("New flat", FavouriteCategories.home(all)?.place?.label)
        assertNull(all.first { it.place.label == "Old flat" }.category)

        // Starring it again (from search) doesn't lose its category.
        repo.saveFavourite(place("New flat", 51.6))
        assertEquals("Home", repo.favourites.first().first { it.place.label == "New flat" }.category)

        // One third button at a time.
        repo.saveFavourite(place("Gym", 51.5), category = "Gym & sport")
        repo.saveFavourite(place("Pool", 51.5), category = "Gym & sport")
        repo.setQuick("osm:Gym")
        repo.setQuick("osm:Pool")
        all = repo.favourites.first()
        assertEquals(listOf("Pool"), all.filter { it.quick }.map { it.place.label })
        repo.renameFavourite("osm:Pool", "  Lido ")
        assertEquals("Lido", repo.favourites.first().first { it.id == "osm:Pool" }.place.label)
        db.close()
    }

    @Test
    fun favouritesSavedBeforeCategoriesSurviveTheUpgrade() = runBlocking {
        val name = "upgrade-test.db"
        app.deleteDatabase(name)
        // The database as version 1 of the app left it (Room's own tables, and a favourite).
        SQLiteDatabase.openOrCreateDatabase(app.getDatabasePath(name).also { it.parentFile?.mkdirs() }, null).use { old ->
            old.execSQL("CREATE TABLE IF NOT EXISTS `saved_trips` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `json` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            old.execSQL("CREATE TABLE IF NOT EXISTS `favourites` (`id` TEXT NOT NULL, `label` TEXT NOT NULL, `address` TEXT, `lat` REAL NOT NULL, `lng` REAL NOT NULL, `osmId` TEXT, `arrivalLat` REAL, `arrivalLng` REAL, PRIMARY KEY(`id`))")
            old.execSQL("CREATE TABLE IF NOT EXISTS `recent_places` (`key` TEXT NOT NULL, `label` TEXT NOT NULL, `address` TEXT, `lat` REAL NOT NULL, `lng` REAL NOT NULL, `osmId` TEXT, `usedAt` INTEGER NOT NULL, PRIMARY KEY(`key`))")
            old.execSQL("CREATE TABLE IF NOT EXISTS `presets` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `json` TEXT NOT NULL, PRIMARY KEY(`id`))")
            old.execSQL("CREATE TABLE IF NOT EXISTS `calibration` (`id` INTEGER NOT NULL, `json` TEXT NOT NULL, PRIMARY KEY(`id`))")
            old.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            old.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '8ea093d6ffa4fdf21f21c73b075c2c89')")
            old.execSQL("INSERT INTO favourites VALUES ('osm:1', 'Corner shop', '1 High St', 51.5, -0.1, 'osm:1', 51.501, -0.101)")
            old.version = 1
        }
        val db = WaypointDatabase.create(app, name)
        val favourites = Repository(db.dao()).favourites.first()
        assertEquals(listOf("Corner shop"), favourites.map { it.place.label })
        assertEquals(LatLng(51.501, -0.101), favourites.single().arrivalPoint)
        assertNull(favourites.single().category)
        assertEquals(false, favourites.single().quick)
        db.close()
    }

    @Test
    fun mapPinsCarryTheirCategorysIconAndColour() {
        val json = Json.parseToJsonElement(favouritesGeoJson(listOf(fav("Flat", "Home"), fav("Wall", "Climbing"), fav("Spot", null)))).jsonObject
        val props = json["features"]!!.jsonArray.map { it.jsonObject["properties"]!!.jsonObject }
        assertEquals(listOf("Home", "custom", "none"), props.map { it["icon"]!!.jsonPrimitive.content })
        assertEquals("#1A73E8", props[0]["color"]!!.jsonPrimitive.content)
        assertEquals("osm:Flat", props[0]["id"]!!.jsonPrimitive.content)
        assertTrue(props.all { Regex("#[0-9A-F]{6}").matches(it["color"]!!.jsonPrimitive.content) })
    }
}
