package com.hans.aura.data

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.encodeToString
import retrofit2.Response

/**
 * Library query cache with a 24h TTL, keyed by stable request keys.
 * Kills the per-navigation refetch lag; expired data is refetched on next use.
 * Manual refresh (Settings > Refresh library) clears everything.
 *
 * Also tracks *when* data last arrived, so Settings can tell the user how
 * fresh what they're looking at actually is. Both stamps are persisted in
 * SharedPreferences ("aura_cache") so they survive restarts — an in-memory
 * stamp alone would reset on every launch and always read as "just now".
 */
object LibraryCache {
    private const val TTL_MS = 24L * 60 * 60 * 1000
    private const val PREFS = "aura_cache"
    private const val KEY_LOADED = "last_loaded_ms"
    private const val KEY_MANUAL = "last_manual_refresh_ms"

    private data class Entry(val savedAtMs: Long, val items: List<JellyfinItem>)
    private val store = ConcurrentHashMap<String, Entry>()
    private var disk: CacheDisk? = null
    @Volatile private var generation = 0L

    @Synchronized
    fun install(disk: CacheDisk?) {
        this.disk = disk
        generation++
        store.clear()
    }

    suspend fun get(key: String): List<JellyfinItem>? {
        peek(key)?.let { return it }
        val fullKey = k(key)
        val version = generation
        val storage = disk ?: return null
        val entry = storage.read(onFailure = { deleteList(fullKey) }) {
            val row = list(fullKey) ?: return@read null
            if (System.currentTimeMillis() - row.savedAtMs >= TTL_MS) {
                deleteList(fullKey)
                return@read null
            }
            try {
                Entry(row.savedAtMs, jellyfinJson.decodeFromString<List<JellyfinItem>>(row.items))
            } catch (_: IllegalArgumentException) {
                deleteList(fullKey)
                null
            }
        } ?: return null
        return synchronized(this) {
            if (version != generation) null else {
                val current = store[fullKey]?.takeIf { System.currentTimeMillis() - it.savedAtMs < TTL_MS }
                val result = current ?: entry
                store[fullKey] = result
                result.items
            }
        }
    }

    private var prefs: android.content.SharedPreferences? = null
    private var lastPersistAtMs = 0L

    /** Last time any entry was written into the cache (i.e. data arrived). */
    @Volatile
    var lastLoadedAtMs: Long = 0L
        private set

    /** Last time the user pressed Settings > Refresh library. */
    @Volatile
    var lastManualRefreshAtMs: Long = 0L
        private set

    /** Called once from AuraApp.onCreate; loads the persisted stamps. */
    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        lastLoadedAtMs = p.getLong(KEY_LOADED, 0L)
        lastManualRefreshAtMs = p.getLong(KEY_MANUAL, 0L)
    }

    /** Non-blocking memory-only lookup for callers that cannot suspend. */
    fun peek(key: String): List<JellyfinItem>? =
        store[k(key)]?.takeIf { System.currentTimeMillis() - it.savedAtMs < TTL_MS }?.items

    @Synchronized
    fun put(key: String, items: List<JellyfinItem>) {
        val fullKey = k(key)
        val entry = Entry(System.currentTimeMillis(), items)
        store[fullKey] = entry
        disk?.write { putList(CachedLibraryList(fullKey, entry.savedAtMs, jellyfinJson.encodeToString(items))) }
        markLoaded()
    }

    @Synchronized
    fun evict(key: String) {
        generation++
        val fullKey = k(key)
        store.remove(fullKey)
        disk?.write { deleteList(fullKey) }
    }

    internal fun tabKeyPrefix(kind: String, userId: String) = "lib_${kind.lowercase()}:$userId:"

    /** Drops every cached list whose (full, already suffixed) key matches.
     *  Needed for mutations that touch a whole FAMILY of keys — a favourite
     *  toggle invalidates every sorted/paged Tracks list, and the sort+dir
     *  suffix means there is no single key to name. */
    @Synchronized
    fun evictWhere(predicate: (String) -> Boolean) {
        generation++
        store.keys.filter(predicate).forEach { store.remove(it) }
        disk?.write { listKeys().filter(predicate).forEach { deleteList(it) } }
    }

    /**
     * Every key is scoped to the library selection, so a list fetched while
     * library X was visible can never be served after X is hidden (and vice
     * versa). With nothing hidden the suffix is empty and keys are unchanged.
     */
    private fun k(key: String): String = key + LibraryFilter.cacheSuffix()

    /** Number of cached lists (logged when a session ends). */
    val size: Int get() = store.size

    /** Wipes cached lists but keeps the stamps: Settings should still be able to
     *  report when the previous batch of data was fetched. */
    @Synchronized
    fun clear() {
        generation++
        store.clear()
        disk?.write { clearLists() }
    }

    /** Prevents cold restoration without changing the current session's lists. */
    @Synchronized
    fun clearDiskLists() {
        disk?.write { clearLists() }
    }

    @Synchronized
    fun evictPlaybackHistoryFromDisk() {
        disk?.write {
            listKeys().filter { key ->
                val plain = key.substringBefore('#')
                plain.startsWith("home_recent:") || plain.startsWith("home_recent_artists:") ||
                    plain.startsWith("artist_top:") ||
                    plain.split(':').any { field ->
                        field.split(',').any { it.equals("PlayCount", true) || it.equals("DatePlayed", true) }
                    }
            }.forEach { deleteList(it) }
        }
    }

    /** Fresh data arrived from the server. Persist at most every 30s — put() is
     *  called dozens of times per screen and each write is a disk edit. */
    private fun markLoaded() {
        lastLoadedAtMs = System.currentTimeMillis()
        val now = System.currentTimeMillis()
        if (now - lastPersistAtMs < 30_000L) return
        lastPersistAtMs = now
        prefs?.edit()?.putLong(KEY_LOADED, lastLoadedAtMs)?.apply()
    }

    /** The user pressed Settings > Refresh library. */
    fun markManualRefresh() {
        lastManualRefreshAtMs = System.currentTimeMillis()
        prefs?.edit()
            ?.putLong(KEY_MANUAL, lastManualRefreshAtMs)
            ?.putLong(KEY_LOADED, lastLoadedAtMs)
            ?.apply()
    }
}

internal fun <T> Response<T>.invalidateHistoryOnSuccess(): Response<T> {
    if (isSuccessful) LibraryCache.evictPlaybackHistoryFromDisk()
    return this
}
