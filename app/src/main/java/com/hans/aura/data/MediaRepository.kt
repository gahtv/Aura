package com.hans.aura.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Main-safe facade over the browse layer.
 *
 * Screens used to call `JellyfinApi` + [LibraryFilter] + [LibraryCache] +
 * [PagedLibrary] + [AtmosBrowse] directly, which meant every new screen had to
 * re-derive the caching/paging/filtering rules (and could forget one — the
 * "hidden library still shows albums" class of bug). ViewModels/state holders
 * now talk to this single object instead:
 *
 *  - every suspend method is `withContext(Dispatchers.IO)`, so callers never
 *    block the main thread on a server round-trip;
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

    private fun api(): JellyfinApi = jellyfin.api

    private fun userId(): String? = sessionProvider()?.userId

    // ---- Browse ------------------------------------------------------------

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
        runCatching { AtmosCatalog.albumIds(api(), uid) }.getOrDefault(emptySet())
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
