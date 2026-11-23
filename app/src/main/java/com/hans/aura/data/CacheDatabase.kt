package com.hans.aura.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

internal val jellyfinJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    encodeDefaults = true
}

@Entity(tableName = "library_lists")
data class CachedLibraryList(@PrimaryKey val key: String, val savedAtMs: Long, val items: String)

@Entity(tableName = "atmos_catalogs")
data class CachedAtmosCatalog(
    @PrimaryKey val key: String,
    val savedAtMs: Long,
    val albumIds: String,
    val trackIds: String
)

@Dao
interface CacheDao {
    @Query("SELECT * FROM library_lists WHERE `key` = :key")
    fun list(key: String): CachedLibraryList?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putList(row: CachedLibraryList)
    @Query("SELECT `key` FROM library_lists")
    fun listKeys(): List<String>
    @Query("DELETE FROM library_lists WHERE `key` = :key")
    fun deleteList(key: String)
    @Query("DELETE FROM library_lists")
    fun clearLists()
    @Query("SELECT * FROM atmos_catalogs WHERE `key` = :key")
    fun atmos(key: String): CachedAtmosCatalog?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putAtmos(row: CachedAtmosCatalog)
    @Query("DELETE FROM atmos_catalogs WHERE `key` = :key")
    fun deleteAtmos(key: String)
    @Query("DELETE FROM atmos_catalogs")
    fun clearAtmos()
}

@Database(entities = [CachedLibraryList::class, CachedAtmosCatalog::class], version = 1, exportSchema = true)
abstract class CacheDatabase : RoomDatabase() {
    abstract fun cacheDao(): CacheDao
}

/** Serialises writes, invalidations and reads without blocking the UI thread. */
class CacheDisk(val dao: CacheDao) {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "aura-cache").apply { isDaemon = true } }
    private val dispatcher = executor.asCoroutineDispatcher()

    fun write(block: CacheDao.() -> Unit) {
        executor.execute {
            try {
                dao.block()
            } catch (e: Exception) {
                android.util.Log.w("AURA_CACHE", "Cache write failed", e)
            }
        }
    }

    suspend fun <T> read(block: CacheDao.() -> T): T = withContext(dispatcher) { dao.block() }

    fun close() = dispatcher.close()
}
