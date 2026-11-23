package com.hans.aura.data

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString

/**
 * Knows which albums and tracks contain an E-AC-3 JOC (Dolby Atmos) stream.
 *
 * One whole-library scan per session (paged audio query with MediaSources),
 * cached in-process. The same scan feeds BOTH the ATMOS badges and the
 * "only Atmos" filter / Atmos browse path, so turning the filter on must never
 * trigger a second walk.
 *
 * With libraries hidden the scan runs per VISIBLE library (ParentId), so a
 * hidden library's tracks never contribute — and never get fetched at all.
 */
object AtmosCatalog {

    /** Album ids AND track ids that carry at least one Atmos audio stream. */
    data class Catalog(
        val albumIds: Set<String> = emptySet(),
        val trackIds: Set<String> = emptySet()
    )

    private val cache = ConcurrentHashMap<String, Catalog>()
    private val scanMutex = Mutex()
    private var disk: CacheDisk? = null
    @Volatile private var generation = 0L

    @Synchronized
    fun install(disk: CacheDisk?) {
        this.disk = disk
        generation++
        cache.clear()
    }

    suspend fun catalog(api: JellyfinApi, userId: String): Catalog {
        val key = userId + LibraryFilter.cacheSuffix()
        cache[key]?.let {
            AtmosFilter.adopt(it)
            return it
        }
        return scanMutex.withLock {
            cache[key]?.let {
                AtmosFilter.adopt(it)
                return@withLock it
            }
            val version = generation
            val storage = disk
            val persisted = storage?.read(onFailure = { deleteAtmos(key) }) {
                val row = atmos(key) ?: return@read null
                if (System.currentTimeMillis() - row.savedAtMs >= 24L * 60 * 60 * 1000) {
                    deleteAtmos(key)
                    return@read null
                }
                try {
                    Catalog(jellyfinJson.decodeFromString<Set<String>>(row.albumIds),
                        jellyfinJson.decodeFromString<Set<String>>(row.trackIds))
                } catch (_: IllegalArgumentException) {
                    deleteAtmos(key)
                    null
                }
            }
            if (persisted != null) {
                val adopted = synchronized(this) {
                    if (version == generation) {
                        cache[key] = persisted
                        AtmosFilter.adopt(persisted)
                        true
                    } else false
                }
                if (adopted) return@withLock persisted
            }
            val atmosAlbums = HashSet<String>()
            val atmosTracks = HashSet<String>()
            val pageSize = 1000

            suspend fun scan(parentId: String?) {
                var start = 0
                while (true) {
                    val page = LibraryFilter.queryItems(
                        api, userId,
                        LibraryFilter.ItemsQuery(
                            includeItemTypes = "Audio",
                            recursive = true,
                            parentId = parentId,
                            fields = "MediaSources",
                            limit = pageSize,
                            startIndex = start
                        )
                    )
                    for (track in page.items) {
                        val isAtmos = track.mediaSources?.any { src ->
                            src.mediaStreams?.any { s ->
                                s.codec.equals("eac3", true) &&
                                    (s.profile?.contains("JOC", true) == true ||
                                        s.profile?.contains("Dolby Atmos", true) == true)
                            } == true
                        } == true
                        if (isAtmos) {
                            atmosTracks.add(track.id)
                            track.albumId?.let { atmosAlbums.add(it) }
                        }
                    }
                    if (page.items.isEmpty() || start + pageSize >= page.totalRecordCount) break
                    start += pageSize
                }
            }

            if (LibraryFilter.isActive()) {
                // One full pass per visible library is cheaper than asking for the
                // union page-by-page, and each pass is exactly one library's audio.
                for (lib in LibraryFilter.visibleLibraries()) scan(lib.id)
            } else {
                scan(null)
            }
            val result = Catalog(atmosAlbums.toSet(), atmosTracks.toSet())
            synchronized(this) {
                if (version == generation) {
                    cache[key] = result
                    AtmosFilter.adopt(result)
                    storage?.write {
                        putAtmos(CachedAtmosCatalog(key, System.currentTimeMillis(),
                            jellyfinJson.encodeToString(result.albumIds), jellyfinJson.encodeToString(result.trackIds)))
                    }
                }
            }
            result
        }
    }

    /** Badge-only callers keep this shorthand (Home shelf, artist covers, search). */
    suspend fun albumIds(api: JellyfinApi, userId: String): Set<String> =
        catalog(api, userId).albumIds

    val size: Int get() = cache.size
    @Synchronized
    fun clear() {
        generation++
        cache.clear()
        disk?.write { clearAtmos() }
        AtmosFilter.adopt(Catalog())
    }
}
