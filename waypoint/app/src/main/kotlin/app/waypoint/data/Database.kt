package app.waypoint.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
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
    version = 1,
    exportSchema = false,
)
abstract class WaypointDatabase : RoomDatabase() {
    abstract fun dao(): WaypointDao

    companion object {
        fun create(context: Context): WaypointDatabase =
            Room.databaseBuilder(context, WaypointDatabase::class.java, "waypoint.db").build()
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

data class Favourite(val id: String, val place: Place, val arrivalPoint: LatLng?)

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
            )
        }
    }

    suspend fun saveFavourite(place: Place, arrivalPoint: LatLng? = null, id: String = placeKey(place)) = dao.saveFavourite(
        FavouriteEntity(id, place.label, place.address, place.latLng.lat, place.latLng.lng, place.osmId, arrivalPoint?.lat, arrivalPoint?.lng),
    )

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
