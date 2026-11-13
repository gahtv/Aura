package com.hans.aura.data

import java.util.concurrent.ConcurrentHashMap

/**
 * Knows which albums contain at least one E-AC-3 JOC (Dolby Atmos) track.
 * Computed by paging through the whole audio library once per session;
 * result cached in-process (and re-derived by the 24h library refresh).
 */
object AtmosCatalog {

    private val cache = ConcurrentHashMap<String, Set<String>>()

    suspend fun albumIds(api: JellyfinApi, userId: String): Set<String> {
        cache[userId]?.let { return it }
        val atmosAlbums = HashSet<String>()
        var start = 0
        val pageSize = 1000
        runCatching {
            while (true) {
                val page = api.getItems(
                    userId = userId,
                    includeItemTypes = "Audio",
                    recursive = true,
                    fields = "MediaSources",
                    limit = pageSize,
                    startIndex = start
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
                        track.albumId?.let { atmosAlbums.add(it) }
                    }
                }
                if (page.items.isEmpty() || start + pageSize >= page.totalRecordCount) break
                start += pageSize
            }
        }
        val result = atmosAlbums.toSet()
        cache[userId] = result
        return result
    }

    fun clear() = cache.clear()
}
