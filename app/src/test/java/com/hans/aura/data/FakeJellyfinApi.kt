package com.hans.aura.data

/**
 * Hand-written fake of [JellyfinApi] — no network, no MockWebServer.
 *
 * Only the browse endpoints the data layer uses are implemented; every other
 * call throws so a test that accidentally reaches the wrong endpoint fails
 * loudly instead of silently passing.
 *
 * Items can be returned per library (`parentId`) so a per-library fan-out can
 * be verified by looking at which parents were actually queried.
 */
class FakeJellyfinApi(
    private val itemsByParent: Map<String?, List<JellyfinItem>> = emptyMap(),
    private val artistsByParent: Map<String?, List<JellyfinItem>> = emptyMap(),
    private val views: List<JellyfinItem> = emptyList(),
    /** Parents whose query fails, to prove one bad library cannot kill a screen. */
    private val failingParents: Set<String?> = emptySet()
) : JellyfinApi {

    /** Every (includeItemTypes, parentId) the data layer actually asked for. */
    val itemQueries = mutableListOf<Pair<String?, String?>>()
    val artistQueries = mutableListOf<String?>()

    /** Server-side item filter / id hydration seen on each getItems call. */
    val itemFilters = mutableListOf<String?>()
    val itemIds = mutableListOf<String?>()

    val queriedParents: List<String?> get() = itemQueries.map { it.second }

    override suspend fun getItems(
        userId: String,
        includeItemTypes: String?,
        excludeItemTypes: String?,
        genres: String?,
        filters: String?,
        enableUserData: Boolean,
        recursive: Boolean,
        parentId: String?,
        artistIds: String?,
        albumArtistIds: String?,
        searchTerm: String?,
        ids: String?,
        sortBy: String,
        sortOrder: String,
        limit: Int,
        startIndex: Int,
        fields: String,
        imageTypeLimit: Int,
        nameStartsWithOrGreater: String?
    ): ItemsResponse {
        itemQueries += includeItemTypes to parentId
        itemFilters += filters
        itemIds += ids
        if (parentId in failingParents) throw IllegalStateException("library $parentId is unreadable")
        val all = itemsByParent[parentId] ?: emptyList()
        return ItemsResponse(
            items = all,
            totalRecordCount = all.size,
            startIndex = startIndex
        )
    }

    override suspend fun getAlbumArtists(
        userId: String,
        sortBy: String,
        sortOrder: String,
        limit: Int,
        startIndex: Int,
        fields: String,
        imageTypeLimit: Int,
        parentId: String?,
        nameStartsWithOrGreater: String?
    ): ItemsResponse {
        artistQueries += parentId
        if (parentId in failingParents) throw IllegalStateException("library $parentId is unreadable")
        val all = artistsByParent[parentId] ?: emptyList()
        return ItemsResponse(items = all, totalRecordCount = all.size, startIndex = startIndex)
    }

    override suspend fun getUserViews(userId: String): ItemsResponse =
        ItemsResponse(items = views, totalRecordCount = views.size)

    private fun nope(name: String): Nothing =
        throw UnsupportedOperationException("FakeJellyfinApi.$name is not part of a browse test")

    override suspend fun authenticateByName(body: AuthRequest): AuthResponse = nope("authenticateByName")
    override suspend fun quickConnectEnabled(): Boolean = nope("quickConnectEnabled")
    override suspend fun quickConnectInitiate(): QuickConnectResult = nope("quickConnectInitiate")
    override suspend fun quickConnectConnect(secret: String): QuickConnectResult = nope("quickConnectConnect")
    override suspend fun authenticateWithQuickConnect(body: QuickConnectAuthRequest): AuthResponse =
        nope("authenticateWithQuickConnect")

    override suspend fun getInstantMix(
        itemId: String,
        userId: String,
        limit: Int,
        fields: String,
        enableUserData: Boolean,
        imageTypeLimit: Int
    ): ItemsResponse = nope("getInstantMix")

    override suspend fun getItem(userId: String, itemId: String): JellyfinItem = nope("getItem")

    override suspend fun instantMix(
        itemId: String,
        userId: String,
        limit: Int,
        fields: String,
        imageTypeLimit: Int
    ): ItemsResponse = nope("instantMix")

    override suspend fun artistInstantMix(
        itemId: String,
        userId: String,
        limit: Int,
        fields: String,
        imageTypeLimit: Int
    ): ItemsResponse = nope("artistInstantMix")

    override suspend fun genreInstantMix(
        name: String,
        userId: String,
        limit: Int,
        fields: String,
        imageTypeLimit: Int
    ): ItemsResponse = nope("genreInstantMix")

    override suspend fun getSimilarArtists(
        itemId: String,
        userId: String,
        limit: Int,
        fields: String
    ): ItemsResponse = nope("getSimilarArtists")

    override suspend fun getPlaylistItems(playlistId: String, userId: String): ItemsResponse =
        nope("getPlaylistItems")

    override suspend fun createPlaylist(
        name: String,
        ids: String?,
        userId: String,
        body: PlaylistRequest
    ): JellyfinItem = nope("createPlaylist")

    override suspend fun addToPlaylist(
        playlistId: String,
        ids: String,
        userId: String?
    ): retrofit2.Response<Unit> = nope("addToPlaylist")

    override suspend fun movePlaylistItem(
        playlistId: String,
        itemId: String,
        newIndex: Int
    ): retrofit2.Response<Unit> = nope("movePlaylistItem")

    override suspend fun removeFromPlaylist(playlistId: String, ids: String): retrofit2.Response<Unit> =
        nope("removeFromPlaylist")

    override suspend fun deletePlaylist(itemId: String): retrofit2.Response<Unit> = nope("deletePlaylist")

    override suspend fun markFavorite(itemId: String, userId: String): retrofit2.Response<UserData> =
        nope("markFavorite")

    override suspend fun unmarkFavorite(itemId: String, userId: String): retrofit2.Response<UserData> =
        nope("unmarkFavorite")

    override suspend fun getLyrics(itemId: String): LyricsResponse = nope("getLyrics")
    override suspend fun searchRemoteLyrics(itemId: String): List<RemoteLyricDto> = nope("searchRemoteLyrics")
    override suspend fun downloadRemoteLyrics(itemId: String, lyricId: String): LyricsResponse = nope("downloadRemoteLyrics")
    override suspend fun setCapabilities(body: CapabilitiesDto): retrofit2.Response<Unit> =
        nope("setCapabilities")

    override suspend fun reportPlaybackStart(body: PlaybackStartInfo): retrofit2.Response<Unit> =
        nope("reportPlaybackStart")

    override suspend fun reportPlaybackProgress(body: PlaybackProgressInfo): retrofit2.Response<Unit> =
        nope("reportPlaybackProgress")

    override suspend fun reportPlaybackStopped(body: PlaybackStopInfo): retrofit2.Response<Unit> =
        nope("reportPlaybackStopped")
}
