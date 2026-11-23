package com.hans.aura.data

import android.util.Log

/**
 * The "Atmos only" browse path: albums and tracks restricted to the ids found by
 * [AtmosCatalog]'s single whole-library scan.
 *
 * Jellyfin cannot filter by audio codec server-side, so the set is discovered
 * once and then hydrated by id (`GET /Items?Ids=…`, 100 per request). Both lists
 * are small (a few dozen albums, a few hundred tracks on the reference library),
 * so they are hydrated whole, sorted CLIENT-SIDE to honour the active sort pill,
 * cached for the 24h TTL, and then paged in memory. That keeps paging and the
 * BACK-restore behaviour identical to every other tab.
 */
object AtmosBrowse {

    private const val TAG = "AURA_ATMOS"
    private const val CHUNK = 100

    private fun cacheKey(type: String, userId: String, sortBy: String, desc: Boolean, favoritesOnly: Boolean) =
        "atmos_${type.lowercase()}:$userId:$sortBy:$desc:${if (favoritesOnly) "fav" else "all"}" +
            LibraryFilter.cacheSuffix()

    private fun sortItems(items: List<JellyfinItem>, sortBy: String, desc: Boolean): List<JellyfinItem> {
        val sorted = when (sortBy) {
            "Album" -> items.sortedBy { (it.album ?: it.name).lowercase() }
            "AlbumArtist" -> items.sortedBy { (it.albumArtist ?: it.artistNames).lowercase() }
            "DateCreated" -> items.sortedBy { it.dateCreated ?: "" }
            "Runtime" -> items.sortedBy { it.runTimeTicks ?: 0L }
            "PlayCount" -> items.sortedBy { it.userData?.playCount ?: 0 }
            "Random" -> items.shuffled()
            else -> items.sortedBy { (it.sortName ?: it.name).lowercase() }
        }
        return if (desc) sorted.reversed() else sorted
    }

    private suspend fun full(
        api: JellyfinApi,
        userId: String,
        ids: Set<String>,
        type: String,
        sortBy: String,
        desc: Boolean,
        favoritesOnly: Boolean
    ): List<JellyfinItem> {
        val key = cacheKey(type, userId, sortBy, desc, favoritesOnly)
        LibraryCache.get(key)?.let { if (it.isNotEmpty()) return it }
        val fetched = mutableListOf<JellyfinItem>()
        for (chunk in ids.toList().chunked(CHUNK)) {
            runCatching {
                api.getItems(
                    userId = userId,
                    includeItemTypes = type,
                    ids = chunk.joinToString(","),
                    recursive = true,
                    limit = CHUNK
                )
            }.onSuccess { fetched += it.items }
                .onFailure { Log.w(TAG, "hydrate type=$type chunk=${chunk.size} failed: ${it.message}") }
        }
        val base = if (favoritesOnly) fetched.filter { Favorites.isFavoriteRaw(it) } else fetched
        val result = sortItems(base.distinctBy { it.id }, sortBy, desc)
        LibraryCache.put(key, result)
        Log.d(TAG, "hydrate type=$type ids=${ids.size} fetched=${result.size} sort=$sortBy desc=$desc fav=$favoritesOnly")
        return result
    }

    suspend fun albums(
        api: JellyfinApi,
        userId: String,
        sortBy: String = "SortName",
        desc: Boolean = false,
        favoritesOnly: Boolean = false,
        startIndex: Int = 0,
        limit: Int = 60
    ): ItemsResponse {
        val all = full(api, userId, AtmosCatalog.catalog(api, userId).albumIds, "MusicAlbum", sortBy, desc, favoritesOnly)
        return ItemsResponse(
            items = all.drop(startIndex).take(limit),
            totalRecordCount = all.size,
            startIndex = startIndex
        )
    }

    suspend fun tracks(
        api: JellyfinApi,
        userId: String,
        sortBy: String = "SortName",
        desc: Boolean = false,
        favoritesOnly: Boolean = false,
        startIndex: Int = 0,
        limit: Int = 60
    ): ItemsResponse {
        val all = full(api, userId, AtmosCatalog.catalog(api, userId).trackIds, "Audio", sortBy, desc, favoritesOnly)
        return ItemsResponse(
            items = all.drop(startIndex).take(limit),
            totalRecordCount = all.size,
            startIndex = startIndex
        )
    }

    fun clear() = LibraryCache.evictWhere { it.startsWith("atmos_") }
}
