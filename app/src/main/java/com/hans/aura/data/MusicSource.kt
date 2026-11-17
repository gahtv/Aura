package com.hans.aura.data

/**
 * The library backend the app browses and streams from.
 *
 * Aura currently ships one implementation, [JellyfinSource]. This interface is
 * the seam a build uses to point at a different backend — a second server of the
 * same kind, or a purely local file index — without any screen, ViewModel or
 * queue builder learning about it. Which implementation a build gets is decided
 * once, in [com.hans.aura.di.AppContainer], from `BuildConfig.MUSIC_SOURCE`.
 *
 * Scope is deliberately narrow: exactly the browse/query surface
 * [MediaRepository] already exposes, plus the single streaming concern the
 * player needs. It is not a mirror of the server API — an implementation is
 * expected to be a thin adapter over whatever it wraps, not to re-derive the
 * caching, filtering or paging rules that [MediaRepository] owns.
 *
 * Screens and their state holders reach it through [SourceQueries], never
 * through one concrete implementation.
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

    // ---- Browse (mirrors MediaRepository, main-safe there) ------------------

    /** Last cached copy of [prefix] (null when absent or expired). */
    fun cached(prefix: String): List<JellyfinItem>?

    /** Stores a browse result under [prefix]. */
    fun cache(prefix: String, items: List<JellyfinItem>)

    /** Runs one browse query, filtered by the current library selection. */
    suspend fun browse(query: LibraryFilter.ItemsQuery, cachePrefix: String? = null): ItemsResponse

    /** Paged list store: accumulated page for a screen key, or null. */
    fun cachedPage(key: String): PagedLibrary.Page?

    fun storePage(key: String, items: List<JellyfinItem>, total: Int)

    /** Single item detail (never filtered: detail screens open for any id). */
    suspend fun item(itemId: String): JellyfinItem?

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
}
