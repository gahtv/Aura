package com.hans.aura.data

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
    }

    private fun userId(): String? = sessionProvider()?.userId

    // ---- Browse ------------------------------------------------------------

    suspend fun recentlyPlayed(limit: Int = 20): List<JellyfinItem> = withContext(Dispatchers.IO) {
        val uid = userId() ?: return@withContext emptyList()
        if (limit <= 0) return@withContext emptyList()
        LibraryFilter.queryItems(api(), uid, LibraryFilter.ItemsQuery(
            includeItemTypes = "Audio", filters = "IsPlayed",
            sortBy = "DatePlayed", sortOrder = "Descending", limit = limit
        )).items
    }

    suspend fun recentArtists(limit: Int = 20): List<JellyfinItem> = withContext(Dispatchers.IO) {
        val uid = userId() ?: return@withContext emptyList()
        if (limit <= 0) return@withContext emptyList()
        val ids = recentlyPlayed(maxOf(100, limit)).asSequence().flatMap { track ->
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
    fun cached(prefix: String): List<JellyfinItem>? = LibraryCache.get(prefix)

    /** Stores a browse result under [prefix] (TTL + selection scoped). */
    fun cache(prefix: String, items: List<JellyfinItem>) {
        LibraryCache.put(prefix, items)
    }

    /**
     * Runs a browse query through [LibraryFilter] (hidden libraries excluded)
     * and caches it under [cachePrefix] when one is given.
     */
    suspend fun browse(
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
    fun cachedPage(key: String): PagedLibrary.Page? = PagedLibrary.get(key)

    fun storePage(key: String, items: List<JellyfinItem>, total: Int) {
        PagedLibrary.put(key, items, total)
    }

    suspend fun createPlaylist(userId: String, name: String, trackIds: List<String>): JellyfinItem {
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
            _playlistRevisions.update { it + (userId to ((it[userId] ?: 0L) + 1)) }
        }
        return created
    }

    /** Item detail (never filtered: detail screens must open for any id). */
    suspend fun item(itemId: String): JellyfinItem? = withContext(Dispatchers.IO) {
        MusicSources.current?.takeIf { it.id != "jellyfin" }?.let { return@withContext it.item(itemId) }
        val uid = userId() ?: return@withContext null
        runCatching { api().getItem(uid, itemId) }.getOrNull()
    }

    // ---- Artist ------------------------------------------------------------

    /** Albums by an album artist, both FLAC and Atmos copies (no dedupe). */
    suspend fun artistAlbums(artistId: String, limit: Int = 100): List<JellyfinItem> =
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
    suspend fun artistTopTracks(artistId: String, limit: Int = 10): List<JellyfinItem> =
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

    suspend fun similarArtists(artistId: String, limit: Int = 20): List<JellyfinItem> =
        withContext(Dispatchers.IO) {
            val uid = userId() ?: return@withContext emptyList()
            runCatching { api().getSimilarArtists(artistId, uid, limit = limit).items }
                .getOrDefault(emptyList())
        }

    // ---- Search / genres ---------------------------------------------------

    /** One query across tracks, albums, artists and playlists (split by caller). */
    suspend fun search(term: String, limit: Int = 60): ItemsResponse = withContext(Dispatchers.IO) {
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
    suspend fun genres(): List<JellyfinItem> = withContext(Dispatchers.IO) {
        val uid = userId() ?: return@withContext emptyList()
        LibraryCache.get("genres:$uid")?.let { if (it.isNotEmpty()) return@withContext it }

        val names = LinkedHashSet<String>()
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
                item.genres.orEmpty().forEach { g ->
                    g.trim().takeIf { it.isNotBlank() }?.let(names::add)
                }
            }
            if (result.items.isEmpty()) break
            total = result.totalRecordCount
            start += 500
            pages++
        }
        val items = names.map { JellyfinItem(id = it, name = it, type = "MusicGenre") }
        LibraryCache.put("genres:$uid", items)
        com.hans.aura.util.AuraLog.i(
            com.hans.aura.util.AuraTag.GENRES,
            "scanned=$pages distinct=${items.size}"
        )
        items
    }

    // ---- Atmos -------------------------------------------------------------

    suspend fun atmosAlbums(
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

    suspend fun atmosTracks(
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

    // ---- Libraries / refresh ----------------------------------------------

    /** Re-reads /UserViews and remembers the list (Settings > Libraries). */
    suspend fun refreshLibraryList(): List<LibraryRef> = withContext(Dispatchers.IO) {
        MusicSources.current?.takeIf { it.id != "jellyfin" }?.let { return@withContext it.refreshLibraryList() }
        val uid = userId() ?: return@withContext emptyList()
        runCatching { LibraryFilter.refreshLibraryList(api(), uid) }.getOrDefault(emptyList())
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
