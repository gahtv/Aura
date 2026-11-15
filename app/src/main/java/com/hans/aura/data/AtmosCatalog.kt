package com.hans.aura.data

import java.util.concurrent.ConcurrentHashMap

/**
 * Knows which albums contain at least one E-AC-3 JOC (Dolby Atmos) track.
 * Computed by paging through the whole audio library once per session;
 * result cached in-process (and re-derived by the 24h library refresh).
 *
 * With libraries hidden the scan runs per VISIBLE library (ParentId), so a
 * hidden library's tracks never contribute an Atmos badge — and never get
 * fetched at all.
 */
object AtmosCatalog {

    private val cache = ConcurrentHashMap<String, Set<String>>()

    suspend fun albumIds(api: JellyfinApi, userId: String): Set<String> {
        val key = userId + LibraryFilter.cacheSuffix()
        cache[key]?.let { return it }
        val atmosAlbums = HashSet<String>()
        val pageSize = 1000

        suspend fun scan(parentId: String?) {
            var start = 0
            runCatching {
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
                            track.albumId?.let { atmosAlbums.add(it) }
                        }
                    }
                    if (page.items.isEmpty() || start + pageSize >= page.totalRecordCount) break
                    start += pageSize
                }
            }
        }

        if (LibraryFilter.isActive()) {
            // One full pass per visible library is cheaper than asking for the
            // union page-by-page, and each pass is exactly one library's audio.
            for (lib in LibraryFilter.visibleLibraries()) scan(lib.id)
        } else {
            scan(null)
        }
        val result = atmosAlbums.toSet()
        cache[key] = result
        return result
    }

    fun clear() = cache.clear()
}
