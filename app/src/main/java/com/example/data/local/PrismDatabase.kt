package com.example.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "observations",
    primaryKeys = ["observedAtMillis", "path"],
    indices = [Index(value = ["path", "observedAtMillis"])]
)
data class ObservationEntity(
    val observedAtMillis: Long,
    val observedAtIso: String,
    val path: String,
    val host: String?,
    val url: String?,
    val state: String,
    val viewers: Int = 0,
    val latencyMs: Long = 0L
)

@Entity(tableName = "site_states")
data class SiteStateEntity(
    @PrimaryKey val observedAtMillis: Long,
    val observedAtIso: String,
    val payloadJson: String
)

@Entity(tableName = "saved_streams")
data class SavedStreamEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val groupName: String,
    val streamUrl: String,
    val subdomain: String? = null,
    val streamPath: String? = null,
    val refererHeader: String? = null,
    val userAgentHeader: String? = null,
    val isPinned: Boolean = true,
    val addedAtMillis: Long = System.currentTimeMillis()
)

@Dao
interface PrismDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertObservations(rows: List<ObservationEntity>)

    @Query("SELECT * FROM observations WHERE observedAtMillis >= :sinceMillis ORDER BY observedAtMillis ASC, path ASC")
    fun observeObservationsSince(sinceMillis: Long): Flow<List<ObservationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSiteState(state: SiteStateEntity)

    @Query("SELECT * FROM site_states ORDER BY observedAtMillis DESC LIMIT 20")
    fun observeRecentSiteStates(): Flow<List<SiteStateEntity>>

    @Query("SELECT * FROM site_states ORDER BY observedAtMillis DESC LIMIT 1")
    suspend fun getLatestSiteState(): SiteStateEntity?

    @Query("SELECT * FROM saved_streams ORDER BY isPinned DESC, addedAtMillis DESC")
    fun observeSavedStreams(): Flow<List<SavedStreamEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSavedStream(stream: SavedStreamEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSavedStreams(streams: List<SavedStreamEntity>)

    @Query("DELETE FROM saved_streams WHERE id = :id")
    suspend fun deleteSavedStreamById(id: Int)

    @Query("DELETE FROM observations")
    suspend fun clearObservationHistory()

    @Query("DELETE FROM site_states")
    suspend fun clearSiteStates()
}

@Database(
    entities = [
        ObservationEntity::class,
        SiteStateEntity::class,
        SavedStreamEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class PrismDatabase : RoomDatabase() {
    abstract fun prismDao(): PrismDao

    companion object {
        @Volatile
        private var INSTANCE: PrismDatabase? = null

        fun getInstance(context: Context): PrismDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    PrismDatabase::class.java,
                    "prismcast_history.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
