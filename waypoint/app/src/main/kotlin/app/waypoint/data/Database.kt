package app.waypoint.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.waypoint.trip.DriveTimeCalibration
import app.waypoint.trip.LatLng
import app.waypoint.trip.ModeChoice
import app.waypoint.trip.Continuation
import app.waypoint.trip.Place
import app.waypoint.trip.Trip
import kotlin.time.Duration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Storage (spec §2.2): saved trips, favourite places, presets and the drive-time calibration.
// Trips and presets are stored as JSON of the pure-Kotlin model so the model can evolve freely.

@Entity(tableName = "saved_trips")
data class SavedTripEntity(
    @PrimaryKey val id: String,
    val name: String,
    val json: String,
    val updatedAt: Long,
)

@Entity(tableName = "favourites")
data class FavouriteEntity(
    @PrimaryKey val id: String,
    val label: String,
    val address: String?,
    val lat: Double,
    val lng: Double,
    val osmId: String?,
    /** Saved car park / entrance for this place, reused when it's added as a stop. */
    val arrivalLat: Double? = null,
    val arrivalLng: Double? = null,
    /** Its category ("Home", "Work", "Food & drink" or your own); null: none. */
    val category: String? = null,
    /** The third quick button on the map (Home and Work have the first two). */
    @ColumnInfo(defaultValue = "0") val quick: Boolean = false,
)

@Entity(tableName = "recent_places")
data class RecentPlaceEntity(
    @PrimaryKey val key: String,
    val label: String,
    val address: String?,
    val lat: Double,
    val lng: Double,
    val osmId: String?,
    val usedAt: Long,
)

@Entity(tableName = "presets")
data class PresetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val json: String,
)

@Entity(tableName = "calibration")
data class CalibrationEntity(
    @PrimaryKey val id: Int = 1,
    val json: String,
)

@Dao
interface WaypointDao {
    @Query("SELECT * FROM saved_trips ORDER BY updatedAt DESC")
    fun savedTrips(): Flow<List<SavedTripEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveTrip(trip: SavedTripEntity)

    @Query("DELETE FROM saved_trips WHERE id = :id")
    suspend fun deleteTrip(id: String)

    @Query("SELECT * FROM favourites ORDER BY label COLLATE NOCASE")
    fun favourites(): Flow<List<FavouriteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveFavourite(f: FavouriteEntity)

    @Query("DELETE FROM favourites WHERE id = :id")
    suspend fun deleteFavourite(id: String)

    @Query("SELECT * FROM favourites WHERE id = :id")
    suspend fun favourite(id: String): FavouriteEntity?

    @Query("UPDATE favourites SET category = NULL WHERE category = :category")
    suspend fun clearCategory(category: String)

    @Query("UPDATE favourites SET category = :category WHERE id = :id")
    suspend fun setCategory(id: String, category: String?)

    @Query("UPDATE favourites SET label = :label WHERE id = :id")
    suspend fun renameFavourite(id: String, label: String)

    @Query("UPDATE favourites SET quick = 0")
    suspend fun clearQuick()

    @Query("UPDATE favourites SET quick = 1 WHERE id = :id")
    suspend fun setQuick(id: String)

    @Query("SELECT * FROM recent_places ORDER BY usedAt DESC LIMIT 20")
    fun recents(): Flow<List<RecentPlaceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun touchRecent(r: RecentPlaceEntity)

    @Query("SELECT * FROM presets ORDER BY name COLLATE NOCASE")
    fun presets(): Flow<List<PresetEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePreset(p: PresetEntity)

    @Query("DELETE FROM presets WHERE id = :id")
    suspend fun deletePreset(id: String)

    @Query("SELECT * FROM calibration WHERE id = 1")
    suspend fun calibration(): CalibrationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveCalibration(c: CalibrationEntity)
}

@Database(
    entities = [SavedTripEntity::class, FavouriteEntity::class, RecentPlaceEntity::class, PresetEntity::class, CalibrationEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class WaypointDatabase : RoomDatabase() {
    abstract fun dao(): WaypointDao

    companion object {
        /** Version 2: favourites get a category and the quick-button flag; existing ones keep everything. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `favourites` ADD COLUMN `category` TEXT")
                db.execSQL("ALTER TABLE `favourites` ADD COLUMN `quick` INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun create(context: Context, name: String = "waypoint.db"): WaypointDatabase =
            Room.databaseBuilder(context, WaypointDatabase::class.java, name).addMigrations(MIGRATION_1_2).build()
    }
}

/** A named mode setup ("Commute: Fastest, no bus, Manual stops of 15 min"). */
@Serializable
data class Preset(
    val id: String,
    val name: String,
    val defaultMode: ModeChoice,
    val defaultContinuation: Continuation,
    val defaultStay: Duration,
)

data class Favourite(
    val id: String,
    val place: Place,
    val arrivalPoint: LatLng?,
    val category: String? = null,
    val quick: Boolean = false,
)

class Repository(private val dao: WaypointDao) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val savedTrips: Flow<List<Trip>> = dao.savedTrips().map { list ->
        list.mapNotNull { runCatching { json.decodeFromString(Trip.serializer(), it.json) }.getOrNull() }
    }

    suspend fun saveTrip(trip: Trip) = dao.saveTrip(
        SavedTripEntity(trip.id, trip.name ?: "Trip", json.encodeToString(Trip.serializer(), trip), System.currentTimeMillis()),
    )

    suspend fun deleteTrip(id: String) = dao.deleteTrip(id)

    val favourites: Flow<List<Favourite>> = dao.favourites().map { list ->
        list.map {
            Favourite(
                id = it.id,
                place = Place(it.osmId, LatLng(it.lat, it.lng), it.label, it.address),
                arrivalPoint = if (it.arrivalLat != null && it.arrivalLng != null) LatLng(it.arrivalLat, it.arrivalLng) else null,
                category = it.category,
                quick = it.quick,
            )
        }
    }

    /**
     * Saves [place] as a favourite. Saving one again keeps its category and quick button (and
     * its car park, unless a new one is given); [category] (Home, Work…) is set when given.
     */
    suspend fun saveFavourite(place: Place, arrivalPoint: LatLng? = null, id: String = placeKey(place), category: String? = null) {
        val old = dao.favourite(id)
        dao.saveFavourite(
            FavouriteEntity(
                id, place.label, place.address, place.latLng.lat, place.latLng.lng, place.osmId,
                arrivalPoint?.lat ?: old?.arrivalLat, arrivalPoint?.lng ?: old?.arrivalLng,
                category = old?.category, quick = old?.quick ?: false,
            ),
        )
        if (category != null) classify(id, category)
    }

    /** Files favourite [id] under [category] (null: none). Home and Work move from any other place. */
    suspend fun classify(id: String, category: String?) {
        val c = FavouriteCategories.normalise(category)
        if (c != null && FavouriteCategories.isUnique(c)) dao.clearCategory(c)
        dao.setCategory(id, c)
    }

    suspend fun renameFavourite(id: String, label: String) {
        if (label.isNotBlank()) dao.renameFavourite(id, label.trim())
    }

    /** Favourite [id] as the third quick button (null: none); one at a time. */
    suspend fun setQuick(id: String?) {
        dao.clearQuick()
        if (id != null) dao.setQuick(id)
    }

    suspend fun deleteFavourite(id: String) = dao.deleteFavourite(id)

    val recents: Flow<List<Place>> = dao.recents().map { list ->
        list.map { Place(it.osmId, LatLng(it.lat, it.lng), it.label, it.address) }
    }

    suspend fun touchRecent(place: Place) = dao.touchRecent(
        RecentPlaceEntity(placeKey(place), place.label, place.address, place.latLng.lat, place.latLng.lng, place.osmId, System.currentTimeMillis()),
    )

    val presets: Flow<List<Preset>> = dao.presets().map { list ->
        list.mapNotNull { runCatching { json.decodeFromString(Preset.serializer(), it.json) }.getOrNull() }
    }

    suspend fun savePreset(p: Preset) = dao.savePreset(PresetEntity(p.id, p.name, json.encodeToString(Preset.serializer(), p)))

    suspend fun deletePreset(id: String) = dao.deletePreset(id)

    suspend fun calibration(): DriveTimeCalibration = dao.calibration()
        ?.let { runCatching { json.decodeFromString(DriveTimeCalibration.serializer(), it.json) }.getOrNull() }
        ?: DriveTimeCalibration()

    suspend fun saveCalibration(c: DriveTimeCalibration) =
        dao.saveCalibration(CalibrationEntity(json = json.encodeToString(DriveTimeCalibration.serializer(), c)))

    companion object {
        fun placeKey(p: Place): String = p.osmId ?: "%.5f,%.5f".format(java.util.Locale.ROOT, p.latLng.lat, p.latLng.lng)
    }
}
