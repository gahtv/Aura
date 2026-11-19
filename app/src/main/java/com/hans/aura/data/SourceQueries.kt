package com.hans.aura.data

/**
 * Browse calls that used to go straight to the server API, routed through the installed
 * [MusicSource] instead.
 *
 * Screens and ViewModels keep their existing query shapes ([LibraryFilter.ItemsQuery] and the
 * named-argument form of `getItems`), so the only change at a call site is the receiver: the
 * source decides whether that query is answered by the server or by another backend.
 */
object SourceQueries {

    private fun source(): MusicSource? = MusicSources.current

    suspend fun removeFromPlaylist(playlistId: String, entryId: String) {
        checkNotNull(source()) { "No music source available" }.removeFromPlaylist(playlistId, entryId)
    }

    suspend fun recentlyPlayed(limit: Int = 20): List<JellyfinItem> =
        runCatching { source()?.recentlyPlayed(limit).orEmpty() }.getOrDefault(emptyList())

    suspend fun recentArtists(limit: Int = 20): List<JellyfinItem> =
        runCatching { source()?.recentArtists(limit).orEmpty() }.getOrDefault(emptyList())

    /** Query-object form: the shape [LibraryFilter] and the ViewModels build. */
    suspend fun items(query: LibraryFilter.ItemsQuery, cachePrefix: String? = null): ItemsResponse =
        source()?.browse(query, cachePrefix) ?: ItemsResponse()

    /**
     * Named-argument form, matching the old `api.getItems(...)` call sites one for one.
     *
     * Parameters that only make sense server-side (filters, parent ids, explicit id hydration)
     * are accepted and folded into the query object; a backend may ignore what it cannot express.
     */
    @Suppress("LongParameterList", "UNUSED_PARAMETER")
    suspend fun items(
        userId: String? = null,
        includeItemTypes: String? = null,
        excludeItemTypes: String? = null,
        genres: String? = null,
        filters: String? = null,
        enableUserData: Boolean = true,
        recursive: Boolean = true,
        parentId: String? = null,
        artistIds: String? = null,
        albumArtistIds: String? = null,
        searchTerm: String? = null,
        ids: String? = null,
        fields: String? = null,
        imageTypeLimit: Int? = null,
        nameStartsWithOrGreater: String? = null,
        sortBy: String = "SortName",
        sortOrder: String = "Ascending",
        limit: Int = 100,
        startIndex: Int = 0
    ): ItemsResponse = items(
        LibraryFilter.ItemsQuery(
            includeItemTypes = includeItemTypes,
            excludeItemTypes = excludeItemTypes,
            genres = genres,
            filters = filters,
            enableUserData = enableUserData,
            fields = fields ?: LibraryFilter.ItemsQuery.DEFAULT_FIELDS,
            imageTypeLimit = imageTypeLimit ?: 1,
            nameStartsWithOrGreater = nameStartsWithOrGreater,
            ids = ids,
            recursive = recursive,
            parentId = parentId,
            artistIds = artistIds,
            albumArtistIds = albumArtistIds,
            searchTerm = searchTerm,
            sortBy = sortBy,
            sortOrder = sortOrder,
            limit = limit,
            startIndex = startIndex
        )
    )

    /** Album artists (the Artists tab and Home's artist shelf). */
    suspend fun artists(
        sortBy: String = "SortName",
        sortOrder: String = "Ascending",
        limit: Int = 100,
        startIndex: Int = 0
    ): ItemsResponse = source()?.artists(sortBy, sortOrder, limit, startIndex) ?: ItemsResponse()

    suspend fun item(itemId: String): JellyfinItem? = source()?.item(itemId)

    suspend fun similar(artistId: String, limit: Int = 20): List<JellyfinItem> =
        source()?.similarArtists(artistId, limit) ?: emptyList()

    suspend fun atmosAlbumIds(): Set<String> = source()?.atmosAlbumIds() ?: emptySet()
}
