package com.hans.aura.data

import com.hans.aura.model.Album
import com.hans.aura.model.Artist
import com.hans.aura.model.MediaItem
import com.hans.aura.model.MediaPage
import com.hans.aura.model.Playlist
import com.hans.aura.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import retrofit2.HttpException

/**
 * Main-safe facade over the browse layer.
 *
 * Screens used to call `JellyfinApi` + [LibraryFilter] + [LibraryCache] +
 * [PagedLibrary] + [AtmosBrowse] directly, which meant every new screen had to
 * re-derive the caching/paging/filtering rules (and could forget one — the
 * "hidden library still shows albums" class of bug). ViewModels/state holders
 * now talk to this single object instead:
 *
 *  - server calls run on `Dispatchers.IO`, so callers never block the main
 *    thread on a server round-trip;
 *  - cached reads are memory-only and cheap ([cachedPage], [cachedGenres]);
 *  - browse queries still go through [LibraryFilter], so library selection and
 *    the Atmos preference keep their exact existing semantics.
 *
 * This is a facade, NOT a new cache: [LibraryCache] (24h TTL) and [PagedLibrary]
 * (session-scoped pages) remain the stores, and the key grammar is unchanged.
 */
class MediaRepository(
    private val jellyfin: JellyfinClient,
    private val sessionProvider: () -> Session? = { jellyfin.auth.session }
) {
    private val _playlistRevisions = MutableStateFlow<Map<String, Long>>(emptyMap())
    internal val playlistRevisions = _playlistRevisions.asStateFlow()

    private fun api(): JellyfinApi = jellyfin.api

    suspend fun removeFromPlaylist(playlistId: String, itemId: String, occurrence: Int) = withContext(Dispatchers.IO) {
        require(itemId.isNotBlank()) { "Missing playlist item id" }
        require(occurrence >= 0) { "Invalid playlist occurrence" }
        val items = api().getPlaylistItems(playlistId, checkNotNull(userId()) { "Not signed in" }).items
        val matches = items.indices.filter { items[it].id == itemId }
        val index = checkNotNull(matches.getOrNull(occurrence)) { "Playlist entry no longer exists" }
        val response = if (matches.size == 1) {
            val entryId = checkNotNull(items[index].playlistItemId?.takeIf { it.isNotBlank() }) {
                "Missing playlist entry id"
            }
            api().removeFromPlaylist(playlistId, entryId)
        } else {
            check(api().getPlaylist(playlistId).itemIds.size <= items.size) {
                "This playlist has tracks you can't see, so one copy can't be removed safely"
            }
            api().updatePlaylist(playlistId, PlaylistItemsUpdate(
                ids = items.filterIndexed { i, _ -> i != index }.map { it.id }
            ))
        }
        if (!response.isSuccessful) throw HttpException(response)
        LibraryCache.evict("col:$playlistId")
        LibraryCache.clearDiskLists()
    }

    private fun userId(): String? = sessionProvider()?.userId

    // ---- Browse ------------------------------------------------------------

    internal suspend fun rawRecentlyPlayed(limit: Int = 20): List<JellyfinItem> = withContext(Dispatchers.IO) {
        val uid = userId() ?: return@withContext emptyList()
        if (limit <= 0) return@withContext emptyList()
        LibraryFilter.queryItems(api(), uid, LibraryFilter.ItemsQuery(
            includeItemTypes = "Audio", filters = "IsPlayed",
            sortBy = "DatePlayed", sortOrder = "Descending", limit = limit
        )).items
    }

    internal suspend fun rawRecentArtists(limit: Int = 20): List<JellyfinItem> = withContext(Dispatchers.IO) {
        val uid = userId() ?: return@withContext emptyList()
        if (limit <= 0) return@withContext emptyList()
        val ids = rawRecentlyPlayed(maxOf(100, limit)).asSequence().flatMap { track ->
            track.artistItems.orEmpty().filter { it.id.isNotBlank() }
                .ifEmpty { track.albumArtists.orEmpty().filter { it.id.isNotBlank() } }
                .asSequence()
        }.map { it.id }.distinct().take(limit).toList()
        if (ids.isEmpty()) return@withContext emptyList()

        val artists = LibraryFilter.queryItems(api(), uid, LibraryFilter.ItemsQuery(
            includeItemTypes = "MusicArtist", ids = ids.joinToString(","), limit = limit
        )).items.associateBy { it.id }
        ids.mapNotNull { artists[it] }
    }

    /**
     * Last cached copy of [prefix] (null when absent or past the 24h TTL).
     *
     * [LibraryCache] / [PagedLibrary] append the library-selection suffix
     * themselves, so callers pass the plain, stable key ("home_recent:…").
     */
    internal suspend fun rawCached(prefix: String): List<JellyfinItem>? = LibraryCache.get(prefix)

    /** Stores a browse result under [prefix] (TTL + selection scoped). */
    internal fun rawCache(prefix: String, items: List<JellyfinItem>) {
        LibraryCache.put(prefix, items)
    }

    /**
     * Runs a browse query through [LibraryFilter] (hidden libraries excluded)
     * and caches it under [cachePrefix] when one is given.
     */
    internal suspend fun rawBrowse(
        query: LibraryFilter.ItemsQuery,
        cachePrefix: String? = null
    ): ItemsResponse = withContext(Dispatchers.IO) {
        MusicSources.current?.takeIf { it.id != "jellyfin" }?.let { return@withContext it.browse(query, cachePrefix) }
        val uid = userId() ?: return@withContext ItemsResponse()
        val result = LibraryFilter.queryItems(api(), uid, query)
        if (cachePrefix != null && result.items.isNotEmpty()) {
            LibraryCache.put(cachePrefix, result.items)
        }
        result
    }

    /** Paged list store: accumulated page for a screen key, or null. */
    internal fun rawCachedPage(key: String): PagedLibrary.Page? = PagedLibrary.get(key)

    internal fun rawStorePage(key: String, items: List<JellyfinItem>, total: Int, requiresRefresh: Boolean = false) {
        PagedLibrary.put(key, items, total, requiresRefresh)
    }

    suspend fun createPlaylist(userId: String, name: String, trackIds: List<String>): Playlist {
        val created = withContext(Dispatchers.IO) {
            api().createPlaylist(
                name = name,
                ids = trackIds.joinToString(","),
                userId = userId,
                body = PlaylistRequest(name = name, ids = trackIds, userId = userId)
            )
        }
        // Serialize invalidation with LibraryViewModel's page writes on Main.
        withContext(Dispatchers.Main.immediate) {
            LibraryCache.evict("home_playlists:$userId")
            val prefix = LibraryCache.tabKeyPrefix("Playlists", userId)
            LibraryCache.evictWhere { it.startsWith(prefix) }
            PagedLibrary.evictWhere { it.startsWith(prefix) }
            LibraryCache.clearDiskLists()
            _playlistRevisions.update { it + (userId to ((it[userId] ?: 0L) + 1)) }
        }
        return created.toDomainItem() as? Playlist
            ?: Playlist(id = created.id, name = created.name)
    }

    /** Item detail (never filtered: detail screens must open for any id). */
    internal suspend fun rawItem(itemId: String): JellyfinItem? = withContext(Dispatchers.IO) {
        MusicSources.current?.takeIf { it.id != "jellyfin" }?.let { return@withContext it.item(itemId) }
        val uid = userId() ?: return@withContext null
        runCatching { api().getItem(itemId = itemId, userId = uid) }.getOrNull()
    }

    // ---- Artist ------------------------------------------------------------

    /** Albums by an album artist, both FLAC and Atmos copies (no dedupe). */
    internal suspend fun rawArtistAlbums(artistId: String, limit: Int = 100): List<JellyfinItem> =
        withContext(Dispatchers.IO) {
        MusicSources.current?.takeIf { it.id != "jellyfin" }?.let { return@withContext it.artistAlbums(artistId, limit) }
            val uid = userId() ?: return@withContext emptyList()
            runCatching {
                LibraryFilter.queryItems(
                    api(), uid,
                    LibraryFilter.ItemsQuery(
                        includeItemTypes = "MusicAlbum",
                        albumArtistIds = artistId,
                        recursive = true,
                        sortBy = "ProductionYear",
                        sortOrder = "Descending",
                        limit = limit
                    )
                ).items
            }.getOrDefault(emptyList())
        }

    /** Most-played tracks of an album artist. */
    internal suspend fun rawArtistTopTracks(artistId: String, limit: Int = 10): List<JellyfinItem> =
        withContext(Dispatchers.IO) {
        MusicSources.current?.takeIf { it.id != "jellyfin" }?.let { return@withContext it.artistTopTracks(artistId, limit) }
            val uid = userId() ?: return@withContext emptyList()
            runCatching {
                LibraryFilter.queryItems(
                    api(), uid,
                    LibraryFilter.ItemsQuery(
                        includeItemTypes = "Audio",
                        albumArtistIds = artistId,
                        recursive = true,
                        sortBy = "PlayCount",
                        sortOrder = "Descending",
                        limit = limit
                    )
                ).items
            }.getOrDefault(emptyList())
        }

    internal suspend fun rawSimilarArtists(artistId: String, limit: Int = 20): List<JellyfinItem> =
        withContext(Dispatchers.IO) {
            val uid = userId() ?: return@withContext emptyList()
            runCatching { api().getSimilarArtists(artistId, uid, limit = limit).items }
                .getOrDefault(emptyList())
        }

    // ---- Search / genres ---------------------------------------------------

    /** One query across tracks, albums, artists and playlists (split by caller). */
    internal suspend fun rawSearch(term: String, limit: Int = 60): ItemsResponse = withContext(Dispatchers.IO) {
        MusicSources.current?.takeIf { it.id != "jellyfin" }?.let { return@withContext it.search(term, limit) }
        val uid = userId() ?: return@withContext ItemsResponse()
        runCatching {
            LibraryFilter.queryItems(
                api(), uid,
                LibraryFilter.ItemsQuery(
                    includeItemTypes = "Audio,MusicAlbum,MusicArtist,Playlist",
                    recursive = true,
                    searchTerm = term,
                    sortBy = "SortName",
                    sortOrder = "Ascending",
                    limit = limit
                )
            )
        }.getOrDefault(ItemsResponse())
    }

    /**
     * Genres actually carried by tracks — never the server's MusicGenre table
     * (it keeps every genre ever seen and most entries match nothing).
     * Cached under "genres:<userId>".
     */
    internal suspend fun rawGenres(): List<JellyfinItem> = withContext(Dispatchers.IO) {
        val uid = userId() ?: return@withContext emptyList()
        LibraryCache.get("genres:$uid")?.let { return@withContext it }

        val genreArt = LinkedHashMap<String, Pair<String?, String?>>()
        var start = 0
        var total = Int.MAX_VALUE
        var pages = 0
        while (start < total && pages < 12) {
            val result = runCatching {
                LibraryFilter.queryItems(
                    api(), uid,
                    LibraryFilter.ItemsQuery(
                        includeItemTypes = "Audio",
                        recursive = true,
                        sortBy = "SortName",
                        sortOrder = "Ascending",
                        limit = 500,
                        startIndex = start,
                        fields = "Genres"
                    )
                )
            }.getOrNull() ?: break
            result.items.forEach { item ->
                val artPair = (item.albumId ?: item.id) to (item.albumPrimaryImageTag ?: item.primaryImageTag)
                item.genres.orEmpty().forEach { g ->
                    val clean = g.trim()
                    if (clean.isNotBlank() && !genreArt.containsKey(clean)) {
                        genreArt[clean] = if (artPair.second != null) artPair else (null to null)
                    }
                }
            }
            if (result.items.isEmpty()) break
            total = result.totalRecordCount
            start += result.items.size
            pages++
        }
        val items = genreArt.entries.sortedBy { it.key.lowercase() }.map { (name, art) ->
            JellyfinItem(
                id = "genre:$name",
                name = name,
                type = "MusicGenre",
                albumId = art.first,
                albumPrimaryImageTag = art.second
            )
        }
        LibraryCache.put("genres:$uid", items)
        com.hans.aura.util.AuraLog.i(
            com.hans.aura.util.AuraTag.GENRES,
            "scanned=$start distinct=${items.size} pages=$pages"
        )
        items
    }

    // ---- Atmos -------------------------------------------------------------

    internal suspend fun rawAtmosAlbums(
        sortBy: String = "SortName",
        desc: Boolean = false,
        favoritesOnly: Boolean = false,
        startIndex: Int = 0,
        limit: Int = 60
    ): ItemsResponse = withContext(Dispatchers.IO) {
        MusicSources.current?.takeIf { it.id != "jellyfin" }?.let { return@withContext it.atmosAlbums(sortBy, desc, favoritesOnly, startIndex, limit) }
        val uid = userId() ?: return@withContext ItemsResponse()
        AtmosBrowse.albums(api(), uid, sortBy, desc, favoritesOnly, startIndex, limit)
    }

    internal suspend fun rawAtmosTracks(
        sortBy: String = "SortName",
        desc: Boolean = false,
        favoritesOnly: Boolean = false,
        startIndex: Int = 0,
        limit: Int = 60
    ): ItemsResponse = withContext(Dispatchers.IO) {
        MusicSources.current?.takeIf { it.id != "jellyfin" }?.let { return@withContext it.atmosTracks(sortBy, desc, favoritesOnly, startIndex, limit) }
        val uid = userId() ?: return@withContext ItemsResponse()
        AtmosBrowse.tracks(api(), uid, sortBy, desc, favoritesOnly, startIndex, limit)
    }

    /** Album ids carrying an E-AC-3 JOC track (one scan per session). */
    suspend fun atmosAlbumIds(): Set<String> = withContext(Dispatchers.IO) {
        MusicSources.current?.takeIf { it.id != "jellyfin" }?.let { return@withContext it.atmosAlbumIds() }
        val uid = userId() ?: return@withContext emptySet()
        AtmosCatalog.albumIds(api(), uid)
    }

    suspend fun atmosCatalog(): AtmosCatalog.Catalog = withContext(Dispatchers.IO) {
        MusicSources.current?.takeIf { it.id != "jellyfin" }?.let { return@withContext it.atmosCatalog() }
        val uid = userId() ?: return@withContext AtmosCatalog.Catalog()
        runCatching { AtmosCatalog.catalog(api(), uid) }.getOrDefault(AtmosCatalog.Catalog())
    }


    // ---- Domain-facing browse -------------------------------------------------

    suspend fun recentlyPlayed(limit: Int = 20): List<MediaItem> = rawRecentlyPlayed(limit).toDomainItems()

    suspend fun recentArtists(limit: Int = 20): List<Artist> = rawRecentArtists(limit).toArtists()

    suspend fun cached(prefix: String): List<MediaItem>? = rawCached(prefix)?.toDomainItems()

    suspend fun browse(
        query: LibraryFilter.ItemsQuery,
        cachePrefix: String? = null
    ): MediaPage<MediaItem> = rawBrowse(query, cachePrefix).toDomainPage()

    suspend fun browseTracks(
        query: LibraryFilter.ItemsQuery,
        cachePrefix: String? = null
    ): MediaPage<Track> = rawBrowse(query, cachePrefix).toDomainPage().typed()

    suspend fun browseAlbums(
        query: LibraryFilter.ItemsQuery,
        cachePrefix: String? = null
    ): MediaPage<Album> = rawBrowse(query, cachePrefix).toDomainPage().typed()

    suspend fun browsePlaylists(
        query: LibraryFilter.ItemsQuery,
        cachePrefix: String? = null
    ): MediaPage<Playlist> = rawBrowse(query, cachePrefix).toDomainPage().typed()

    fun cachedPage(key: String): MediaPage<MediaItem>? = rawCachedPage(key)?.let {
        MediaPage(items = it.items.toDomainItems(), total = it.total)
    }

    fun cachedPageRequiresRefresh(key: String): Boolean = rawCachedPage(key)?.requiresRefresh == true

    fun hasCachedPage(key: String): Boolean = rawCachedPage(key) != null

    suspend fun cachedLibraryPage(key: String): MediaPage<MediaItem>? {
        cachedPage(key)?.let { return it }
        val cached = rawCached(key)
        if (cached.isNullOrEmpty()) return null
        rawStorePage(key, cached, Int.MAX_VALUE, requiresRefresh = true)
        return MediaPage(items = cached.toDomainItems(), total = Int.MAX_VALUE)
    }

    suspend fun libraryPage(
        key: String,
        kind: String,
        sortBy: String,
        desc: Boolean,
        likedOnly: Boolean,
        atmos: Boolean,
        letter: String?,
        startIndex: Int,
        limit: Int,
        replace: Boolean,
        valid: () -> Boolean = { true }
    ): MediaPage<MediaItem> = withContext(Dispatchers.IO) {
        val order = if (desc) "Descending" else "Ascending"
        val result = when {
            atmos -> if (kind == "Tracks") {
                rawAtmosTracks(sortBy, desc, likedOnly, startIndex, limit)
            } else {
                rawAtmosAlbums(sortBy, desc, likedOnly, startIndex, limit)
            }
            likedOnly -> rawBrowseLiked(sortBy, order, startIndex, limit)
            letter != null -> rawLetterPage(kind, letter, startIndex, limit)
            kind == "Artists" -> MusicSources.current?.takeIf { it.id != "jellyfin" }
                ?.artists(sortBy, order, limit, startIndex)
                ?: rawArtists(sortBy, order, limit, startIndex)
            kind == "Albums" -> rawBrowse(LibraryFilter.ItemsQuery(
                includeItemTypes = "MusicAlbum", recursive = true,
                sortBy = sortBy, sortOrder = order, limit = limit, startIndex = startIndex
            ))
            kind == "Tracks" -> rawBrowse(LibraryFilter.ItemsQuery(
                includeItemTypes = "Audio", recursive = true,
                sortBy = sortBy, sortOrder = order, limit = limit, startIndex = startIndex
            ))
            kind == "Playlists" -> rawBrowse(LibraryFilter.ItemsQuery(
                includeItemTypes = "Playlist", recursive = true,
                sortBy = sortBy, sortOrder = order, limit = limit, startIndex = startIndex
            ))
            else -> throw IllegalArgumentException("Unknown kind $kind")
        }
        if (!valid()) throw CancellationException("stale library page")
        val previous = if (replace) emptyList() else rawCachedPage(key)?.items.orEmpty()
        val merged = if (replace) result.items else previous + result.items
        val total = if (letter != null) {
            if (result.items.size < 60) startIndex + result.items.size else Int.MAX_VALUE
        } else {
            result.totalRecordCount
        }
        rawStorePage(key, merged, total)
        rawCache(key, merged.take(60))
        MediaPage(items = merged.toDomainItems(), total = total)
    }

    internal suspend fun rawBrowseLiked(
        sortBy: String,
        sortOrder: String,
        startIndex: Int,
        limit: Int
    ): ItemsResponse {
        val uid = userId() ?: return ItemsResponse()
        return Favorites.browseLiked(api(), uid, sortBy, sortOrder, startIndex, limit)
    }

    internal suspend fun rawLetterPage(
        kind: String,
        letter: String,
        startIndex: Int,
        limit: Int
    ): ItemsResponse {
        val uid = userId() ?: return ItemsResponse()
        suspend fun onePage(parentId: String?, start: Int, size: Int): ItemsResponse =
            if (kind == "Artists") {
                api().getAlbumArtists(
                    userId = uid,
                    sortBy = "SortName",
                    sortOrder = "Ascending",
                    limit = size,
                    startIndex = start,
                    fields = "PrimaryImageAspectRatio",
                    imageTypeLimit = 1,
                    parentId = parentId,
                    nameStartsWithOrGreater = letter
                )
            } else {
                rawBrowse(LibraryFilter.ItemsQuery(
                    includeItemTypes = "MusicAlbum",
                    recursive = true,
                    sortBy = "SortName",
                    sortOrder = "Ascending",
                    limit = size,
                    startIndex = start,
                    parentId = parentId,
                    nameStartsWithOrGreater = letter
                ))
            }

        if (!LibraryFilter.isActive()) return onePage(null, startIndex, limit)
        val libs = LibraryFilter.visibleLibraries().filter {
            it.collectionType == null || it.collectionType.equals("music", ignoreCase = true)
        }
        if (libs.isEmpty()) return ItemsResponse()
        val wanted = startIndex + limit
        val responses = libs.mapNotNull { lib ->
            runCatching { onePage(lib.id, 0, wanted) }.getOrNull()
        }
        val all = responses.flatMap { it.items }
            .distinctBy { it.id }
            .sortedBy { (it.sortName ?: it.name).lowercase() }
        return ItemsResponse(
            items = all.drop(startIndex).take(limit),
            totalRecordCount = responses.sumOf { it.totalRecordCount },
            startIndex = startIndex
        )
    }

    internal suspend fun rawArtists(
        sortBy: String,
        sortOrder: String,
        limit: Int,
        startIndex: Int
    ): ItemsResponse = withContext(Dispatchers.IO) {
        val uid = userId() ?: return@withContext ItemsResponse()
        LibraryFilter.queryAlbumArtists(api(), uid, sortBy, sortOrder, limit, startIndex)
    }

    suspend fun artists(
        sortBy: String = "SortName",
        sortOrder: String = "Ascending",
        limit: Int = 100,
        startIndex: Int = 0
    ): MediaPage<Artist> = withContext(Dispatchers.IO) {
        val raw = MusicSources.current?.takeIf { it.id != "jellyfin" }?.artists(sortBy, sortOrder, limit, startIndex)
            ?: rawArtists(sortBy, sortOrder, limit, startIndex)
        raw.toDomainPage().typed()
    }

    suspend fun item(itemId: String): MediaItem? = rawItem(itemId)?.toDomainItem()

    suspend fun artistAlbums(artistId: String, limit: Int = 100): List<Album> =
        rawArtistAlbums(artistId, limit).toAlbums()

    suspend fun artistTopTracks(artistId: String, limit: Int = 10): List<Track> =
        rawArtistTopTracks(artistId, limit).toTracks()

    suspend fun similarArtists(artistId: String, limit: Int = 20): List<Artist> =
        rawSimilarArtists(artistId, limit).toArtists()

    suspend fun search(term: String, limit: Int = 60): MediaPage<MediaItem> =
        rawSearch(term, limit).toDomainPage()

    suspend fun genres(): List<com.hans.aura.model.Genre> = rawGenres().toGenres()

    suspend fun atmosAlbums(
        sortBy: String = "SortName",
        desc: Boolean = false,
        favoritesOnly: Boolean = false,
        startIndex: Int = 0,
        limit: Int = 60
    ): MediaPage<Album> = rawAtmosAlbums(sortBy, desc, favoritesOnly, startIndex, limit).toDomainPage().typed()

    suspend fun atmosTracks(
        sortBy: String = "SortName",
        desc: Boolean = false,
        favoritesOnly: Boolean = false,
        startIndex: Int = 0,
        limit: Int = 60
    ): MediaPage<Track> = rawAtmosTracks(sortBy, desc, favoritesOnly, startIndex, limit).toDomainPage().typed()

    suspend fun browseLiked(
        sortBy: String = "SortName",
        sortOrder: String = "Ascending",
        startIndex: Int = 0,
        limit: Int = 60
    ): MediaPage<Track> = rawBrowseLiked(sortBy, sortOrder, startIndex, limit).toDomainPage().typed()

    internal suspend fun rawPlaylistItems(playlistId: String): List<JellyfinItem> = withContext(Dispatchers.IO) {
        val uid = checkNotNull(userId()) { "Not signed in" }
        api().getPlaylistItems(playlistId, uid).items
    }

    suspend fun playlistItems(playlistId: String): List<Track> = rawPlaylistItems(playlistId).toTracks()

    suspend fun addToPlaylist(playlistId: String, trackIds: List<String>) = withContext(Dispatchers.IO) {
        val uid = checkNotNull(userId()) { "Not signed in" }
        val resp = api().addToPlaylist(playlistId, trackIds.joinToString(","), uid)
        if (!resp.isSuccessful) throw HttpException(resp)
        LibraryCache.evict("col:$playlistId")
        LibraryCache.evict("home_playlists:$uid")
        LibraryCache.clearDiskLists()
    }

    suspend fun movePlaylistItem(playlistId: String, itemId: String, newIndex: Int) = withContext(Dispatchers.IO) {
        val resp = api().movePlaylistItem(playlistId, itemId, newIndex)
        if (!resp.isSuccessful) throw HttpException(resp)
        LibraryCache.evict("col:$playlistId")
        LibraryCache.clearDiskLists()
    }

    suspend fun toggleFavorite(item: MediaItem): Boolean? = withContext(Dispatchers.IO) {
        val uid = userId() ?: return@withContext null
        Favorites.toggle(api(), uid, item)
    }

    suspend fun preferredLyrics(itemId: String): List<LyricLine> = withContext(Dispatchers.IO) {
        LyricsStore.fetchPreferred(api(), itemId)
    }

    internal suspend fun rawInstantMix(itemId: String, limit: Int): List<JellyfinItem> = withContext(Dispatchers.IO) {
        val uid = userId() ?: return@withContext emptyList()
        api().instantMix(itemId, userId = uid, limit = limit).items
    }

    internal suspend fun rawArtistInstantMix(artistId: String, limit: Int): List<JellyfinItem> = withContext(Dispatchers.IO) {
        val uid = userId() ?: return@withContext emptyList()
        api().artistInstantMix(artistId, userId = uid, limit = limit).items
    }

    internal suspend fun rawGenreInstantMix(name: String, limit: Int): List<JellyfinItem> = withContext(Dispatchers.IO) {
        val uid = userId() ?: return@withContext emptyList()
        api().genreInstantMix(name, userId = uid, limit = limit).items
    }

    private fun List<JellyfinItem>.mixTracks(): List<Track> =
        filter { it.type.isEmpty() || it.type == "Audio" }.toTracks()

    suspend fun trackMix(track: Track, limit: Int): List<Track> = rawInstantMix(track.id, limit).mixTracks()

    suspend fun albumMix(album: Album, limit: Int): List<Track> = rawInstantMix(album.id, limit).mixTracks()

    suspend fun artistMix(artist: Artist, limit: Int): List<Track> = rawArtistInstantMix(artist.id, limit).mixTracks()

    suspend fun genreMix(name: String, limit: Int): List<Track> = rawGenreInstantMix(name, limit).mixTracks()

    // ---- Libraries / refresh ----------------------------------------------

    /** Re-reads /UserViews and remembers the list (Settings > Libraries). */
    suspend fun refreshLibraryList(): List<LibraryRef> = withContext(Dispatchers.IO) {
        MusicSources.current?.takeIf { it.id != "jellyfin" }?.let { return@withContext it.refreshLibraryList() }
        val uid = userId() ?: return@withContext emptyList()
        LibraryFilter.refreshLibraryList(api(), uid)
    }

    fun knownLibraries(): List<LibraryRef> = Prefs.knownLibraries

    /** Manual refresh: drops every cached list and stamps the refresh time. */
    suspend fun refreshAll() = withContext(Dispatchers.IO) {
        LibraryFilter.clearCaches()
        AtmosBrowse.clear()
        LibraryCache.markManualRefresh()
    }

    /** Library selection changed: the selection-scoped caches must go. */
    fun clearCaches() = LibraryFilter.clearCaches()
}
