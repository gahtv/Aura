package com.hans.aura.data

import android.util.Log

/**
 * Browse queries routed through the configured [MusicSource].
 *
 * State holders used to reach for [LibraryFilter.queryItems] — or, when they
 * happened to hold the api, for `getItems` / `getAlbumArtists` directly — which
 * tied every browse path to one backend. [MusicSources.current] already knows
 * which source this build browses, so the queries ask it instead:
 *
 *  - [items] runs a library-selection-aware browse query: the source owns the
 *    caching/paging/hidden-library rules, callers own the query shape;
 *  - [item] resolves the full item behind a stub for a detail screen (never
 *    filtered: a detail screen must open for any id it was handed);
 *  - [atmosAlbumIds] is the source's object-audio album set (one scan per
 *    session, cached by the source).
 *
 * The same rule as the rest of the seam applies: no caller learns what the
 * source actually is, so a build pointed at a different backend browses with
 * exactly the code below.
 *
 * Two queries deliberately stay outside: the album-artist endpoint has no
 * counterpart on [MusicSource] yet (that is a new interface method, not a
 * caller's business), and the A-Z jump needs a query parameter
 * [LibraryFilter.ItemsQuery] does not carry. Both still go through
 * [LibraryFilter] / the api and should move here when the seam grows.
 */
object SourceQueries {

    private const val TAG = "AURA_SRC"

    /** The source this process browses; null only before startup / in JVM tests. */
    private fun source(): MusicSource? = MusicSources.current

    /** Nothing to query through: an empty result keeps every caller's shape. */
    private fun unavailable(): ItemsResponse {
        Log.w(TAG, "no library source installed — returning an empty result")
        return ItemsResponse()
    }

    /**
     * One browse query, filtered by the current library selection.
     *
     * [cachePrefix] asks the source to keep the result in its list cache under
     * that plain key; callers that cache the page themselves leave it null.
     */
    suspend fun items(
        query: LibraryFilter.ItemsQuery,
        cachePrefix: String? = null
    ): ItemsResponse {
        val source = source() ?: return unavailable()
        return source.browse(query, cachePrefix)
    }

    /**
     * Same query, in the named-argument shape callers already had: one field per
     * [LibraryFilter.ItemsQuery] property, so a screen never builds the query
     * object (or knows it exists).
     *
     * [filters] is the server-side item filter (`IsFavorite`) the Liked tab is
     * built on; [ids] hydrates a known id set and is a direct lookup, never
     * split per library.
     */
    suspend fun items(
        includeItemTypes: String? = null,
        excludeItemTypes: String? = null,
        genres: String? = null,
        filters: String? = null,
        recursive: Boolean = true,
        parentId: String? = null,
        artistIds: String? = null,
        albumArtistIds: String? = null,
        searchTerm: String? = null,
        ids: String? = null,
        sortBy: String = "SortName",
        sortOrder: String = "Ascending",
        limit: Int = 100,
        startIndex: Int = 0,
        fields: String = LibraryFilter.ItemsQuery.DEFAULT_FIELDS,
        imageTypeLimit: Int = 1,
        cachePrefix: String? = null
    ): ItemsResponse = items(
        LibraryFilter.ItemsQuery(
            includeItemTypes = includeItemTypes,
            excludeItemTypes = excludeItemTypes,
            genres = genres,
            filters = filters,
            recursive = recursive,
            parentId = parentId,
            artistIds = artistIds,
            albumArtistIds = albumArtistIds,
            searchTerm = searchTerm,
            ids = ids,
            sortBy = sortBy,
            sortOrder = sortOrder,
            limit = limit,
            startIndex = startIndex,
            fields = fields,
            imageTypeLimit = imageTypeLimit
        ),
        cachePrefix
    )

    /** Full item for [itemId] (tags, Overview), or null when it cannot be read. */
    suspend fun item(itemId: String): JellyfinItem? = source()?.item(itemId)

    /** Album ids carrying an object-audio track; empty when the source cannot say. */
    suspend fun atmosAlbumIds(): Set<String> = source()?.atmosAlbumIds() ?: emptySet()
}
