package com.hans.aura.data

/**
 * The library backend the app browses and streams from.
 *
 * Aura currently ships one implementation, [JellyfinSource]. This interface is
 * the seam a build uses to point at a different backend — a second server of the
 * same kind, or a purely local file index — without any screen, ViewModel or
 * queue builder learning about it. Which implementation a build gets is decided
 * once, in [com.hans.aura.di.BackendFactory], by the active product flavor.
 *
 * Scope is deliberately narrow: exactly the browse/query surface
 * [MediaRepository] already exposes, plus the single streaming concern the
 * player needs. It is not a mirror of the server API — an implementation is
 * expected to be a thin adapter over whatever it wraps, not to re-derive the
 * caching, filtering or paging rules that [MediaRepository] owns.
 */
interface MusicSource {

    /** Stable id for logs and diagnostics ("jellyfin", "local", …). */
    val id: String

    /** Human-readable backend name; diagnostics only, never UI copy. */
    val displayName: String

    // ---- Streaming ---------------------------------------------------------

    /**
     * Direct-play URI for [itemId].
     *
     * Non-suspending on purpose: media items are assembled while a queue is
     * built, often on the main thread, and for a backend like the current one
     * this is pure string work. A backend that must talk to its server to mint
     * a URI should return its own stable handle here and do the round-trip in
     * [streamTarget] instead.
     *
     * [container] is the item's media-source container when the caller knows it
     * (it keeps the original extension on the URL); [serverUrl] is the host the
     * item was browsed from, when the caller has it.
     */
    fun streamUri(itemId: String, container: String? = null, serverUrl: String? = null): String

    /**
     * Everything needed to open [itemId]: the URI plus the headers that must
     * accompany the request. Suspendable because a backend may need I/O (a
     * short-lived token, a signed URL) before it can answer.
     *
     * Headers are resolved per call and never cached on the instance, so a
     * re-sign-in or a server change is picked up on the very next track.
     */
    suspend fun streamTarget(
        itemId: String,
        container: String? = null,
        serverUrl: String? = null
    ): PlaybackTarget = PlaybackTarget(url = streamUri(itemId, container, serverUrl))

    /**
     * Artwork URL for [itemId], or null to let the caller fall back to its own convention.
     *
     * A backend that serves cover art itself (a packaged library, a different server) answers
     * here; the default keeps the existing per-server path byte-identical.
     */
    fun artworkUrl(itemId: String, tag: String?, maxWidth: Int): String? = null

    /** True when this source serves its own cover art (see [artworkUrl]). */
    val servesArtwork: Boolean get() = false

    /**
     * True when this backend tracks the *device* as a server-side play session:
     * it accepts the `/Sessions/Playing*` reports and exposes the `/socket`
     * remote-control channel.
     *
     * One flag covers both because they are one server concept — a Jellyfin
     * device session. Defaults to true so the server-backed build keeps
     * announcing itself exactly as before; a backend that has no such endpoints
     * (a plain index that only serves bytes) turns it off and the player stops
     * posting reports and stops opening the websocket.
     */
    val reportsPlayState: Boolean get() = true

    /**
     * True when this backend keeps its own play history and can answer a
     * "Recently played" shelf. Jellyfin records plays through the device session
     * ([reportsPlayState]); an index has to be told explicitly through
     * [recordPlay], but it still keeps album-level history.
     */
    val hasPlayHistory: Boolean get() = reportsPlayState

    /**
     * Item type the history shelf is built from. Jellyfin records plays per
     * track, so the shelf is tracks; a backend that only keeps album-level
     * history asks for albums instead.
     */
    val playHistoryItemType: String get() = "Audio"

    suspend fun recentlyPlayed(limit: Int = 20): List<JellyfinItem> =
        if (!hasPlayHistory || limit <= 0) emptyList() else runCatching {
            browse(LibraryFilter.ItemsQuery(
                includeItemTypes = playHistoryItemType,
                sortBy = "DatePlayed", sortOrder = "Descending", limit = limit
            )).items
        }.getOrDefault(emptyList())

    suspend fun recentArtists(limit: Int = 20): List<JellyfinItem> =
        if (!hasPlayHistory || playHistoryItemType != "Audio" || limit <= 0) emptyList() else runCatching {
            browse(LibraryFilter.ItemsQuery(
                includeItemTypes = "MusicArtist",
                sortBy = "DatePlayed", sortOrder = "Descending", limit = limit
            )).items
        }.getOrDefault(emptyList())

    /**
     * Record one play. Backends with their own history (the index) implement
     * this; Jellyfin learns it from the session reports and leaves it alone.
     * Must never throw: playback does not wait on it.
     */
    fun recordPlay(itemId: String) {}

    /**
     * Lyrics for [itemId] as LRC text, or null when this backend has none.
     *
     * Defaults to null so the server-backed source keeps answering through the
     * existing /Audio/{id}/lyrics path; a backend that owns its lyrics (the
     * local index does) answers here instead and is asked first.
     */
    suspend fun lyrics(itemId: String): String? = null

    /**
     * Word-timed lyrics for [itemId], or null when this backend cannot time
     * individual words.
     *
     * Defaults to null so a source that only has LRC keeps answering through
     * [lyrics] and the pane renders exactly what it always has; a backend that
     * does time words opts in and the pane gains the karaoke highlight. Callers
     * must treat an empty list like null (nothing to show).
     */
    suspend fun lyricsTimed(itemId: String): List<TimedLyricLine>? = null

    val supportsProviderLyrics: Boolean get() = false

    suspend fun searchProviderLyrics(itemId: String): ProviderLyricsResult<List<ProviderLyricMatch>> =
        ProviderLyricsResult.Unavailable

    suspend fun saveProviderLyrics(itemId: String, matchId: String): ProviderLyricsResult<Unit> =
        ProviderLyricsResult.Unavailable

    /**
     * True when this backend can serve a looping motion clip for an album, so
     * detail and now-playing surfaces may draw it behind the static cover.
     *
     * Defaults to false: a backend without the capability keeps a single
     * decoder (the audio one) on TV hardware and draws nothing extra.
     */
    val servesMotionArtwork: Boolean get() = false

    /**
     * URL of the motion loop for [itemId]'s album, or null when this backend has
     * none for it.
     *
     * Non-suspending like [streamUri] because it is pure string work for the
     * backends that answer it. A URL that turns out to be absent (a 404) must be
     * treated by the caller as "no motion" and silently keep the static cover.
     */
    fun motionArtworkUrl(itemId: String): String? = null

    // ---- Browse (mirrors MediaRepository, main-safe there) ------------------

    /** Last cached copy of [prefix] (null when absent or expired). */
    suspend fun cached(prefix: String): List<JellyfinItem>?

    /** Stores a browse result under [prefix]. */
    fun cache(prefix: String, items: List<JellyfinItem>)

    /** Runs one browse query, filtered by the current library selection. */
    suspend fun browse(query: LibraryFilter.ItemsQuery, cachePrefix: String? = null): ItemsResponse

    /** Remove the zero-based occurrence of an item among same-id rows; failures propagate. */
    suspend fun removeFromPlaylist(playlistId: String, itemId: String, occurrence: Int) {
        throw UnsupportedOperationException("Playlist removal is not supported")
    }

    /** Album artists for the Artists tab and Home shelf. */
    suspend fun artists(
        sortBy: String = "SortName",
        sortOrder: String = "Ascending",
        limit: Int = 100,
        startIndex: Int = 0
    ): ItemsResponse = browse(LibraryFilter.ItemsQuery(
        includeItemTypes = "MusicArtist", sortBy = sortBy, sortOrder = sortOrder,
        limit = limit, startIndex = startIndex
    ))

    /** Paged list store: accumulated page for a screen key, or null. */
    fun cachedPage(key: String): PagedLibrary.Page?

    fun storePage(key: String, items: List<JellyfinItem>, total: Int)

    /** Single item detail (never filtered: detail screens open for any id). */
    suspend fun item(itemId: String): JellyfinItem?

    /**
     * Editorial bio for artist [artistId], or null when this backend has none.
     *
     * Defaults to null so a server-backed source keeps rendering the artist's
     * Overview exactly as before; a backend whose copy lives outside the item
     * (the index keeps its own bios) answers here and the detail screens prefer
     * it over the item field. Callers must treat blank like null.
     */
    suspend fun artistBio(artistId: String): String? = null

    /**
     * Editorial bio for album (or any collection) [itemId], or null when this
     * backend has none. Same fallback rule as [artistBio]; suspends because the
     * answer may be a second round-trip.
     */
    suspend fun albumBio(itemId: String): String? = null

    suspend fun artistAlbums(artistId: String, limit: Int = 100): List<JellyfinItem>

    suspend fun artistTopTracks(artistId: String, limit: Int = 10): List<JellyfinItem>

    suspend fun similarArtists(artistId: String, limit: Int = 20): List<JellyfinItem>

    /** One query across tracks, albums, artists and playlists. */
    suspend fun search(term: String, limit: Int = 60): ItemsResponse

    /** Genres actually carried by tracks. */
    suspend fun genres(): List<JellyfinItem>

    suspend fun atmosAlbums(
        sortBy: String = "SortName",
        desc: Boolean = false,
        favoritesOnly: Boolean = false,
        startIndex: Int = 0,
        limit: Int = 60
    ): ItemsResponse

    suspend fun atmosTracks(
        sortBy: String = "SortName",
        desc: Boolean = false,
        favoritesOnly: Boolean = false,
        startIndex: Int = 0,
        limit: Int = 60
    ): ItemsResponse

    /** Album ids carrying an object-audio track (one scan per session). */
    suspend fun atmosAlbumIds(): Set<String>

    suspend fun atmosCatalog(): AtmosCatalog.Catalog

    // ---- Catalog (search + import into the library) -----------------------

    /**
     * True when this backend can search a catalog it does not yet serve and pull
     * an album into the library.
     *
     * Defaults to false so a server-backed build renders exactly what it always
     * has: the search screen only grows a "From the catalog" section when a
     * source opts in.
     */
    val supportsCatalogImport: Boolean get() = false

    /**
     * Catalog albums matching [term] that are *not* the library itself.
     *
     * Returns neutral [CatalogHit]s rather than library items on purpose — a hit
     * has no playable tracks and no user data. Default: nothing.
     */
    suspend fun catalogSearch(term: String): List<CatalogHit> = emptyList()

    /**
     * Starts an import of catalog album [id]; returns the backend job id to poll
     * with [catalogImportState], or null when the request could not be started.
     */
    suspend fun importFromCatalog(id: String): String? = null

    /** Current state of an import job ("queued", "downloading", "ready", …), or null. */
    suspend fun catalogImportState(jobId: String): String? = null

    // ---- Libraries / refresh ----------------------------------------------

    suspend fun refreshLibraryList(): List<LibraryRef>

    fun knownLibraries(): List<LibraryRef>

    /** Manual refresh: drops every cached list and stamps the refresh time. */
    suspend fun refreshAll()

    /** Library selection changed: selection-scoped caches must go. */
    fun clearCaches()
}

/** A playable resource: where to fetch it, and what to send with the request. */
data class PlaybackTarget(
    val url: String,
    val headers: Map<String, String> = emptyMap()
)

/**
 * Process-wide handle on the source the container built.
 *
 * Media items are also assembled outside any container scope (the queue helper
 * is a top-level function, and the playback service is constructed by the
 * platform), so those call sites read the installed source from here instead of
 * assembling URLs themselves. [com.hans.aura.di.AppContainer] installs it once
 * during `Application.onCreate`, before any player or UI exists.
 */
object MusicSources {

    @Volatile
    private var installed: MusicSource? = null

    /** The source this process runs on; null only before startup / in JVM tests. */
    val current: MusicSource? get() = installed

    fun install(source: MusicSource) {
        installed = source
    }

    /** Test hook: forget the installed source (a Robolectric app start installs one). */
    fun reset() {
        installed = null
    }
}
