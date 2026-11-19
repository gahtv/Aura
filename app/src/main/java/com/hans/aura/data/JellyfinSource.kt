package com.hans.aura.data

import androidx.core.text.HtmlCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * The server-backed [MusicSource]: a thin adapter over the existing
 * [JellyfinClient] / [MediaRepository], with no logic of its own.
 *
 * Every browse call forwards straight to [media]; the streaming side keeps the
 * two rules the player already relied on:
 *
 *  - the direct-play URL is `…/Audio/{id}/stream.{container}?static=true`, so
 *    the original bitstream (FLAC, E-AC-3 JOC) is served untouched, and
 *  - the Authorization header is built per request from the CURRENT session, so
 *    a re-sign-in or a server change can never stream with a stale token.
 */
class JellyfinSource(
    private val client: JellyfinClient,
    private val media: MediaRepository,
    private val fetchLyrics: suspend (String) -> List<LyricLine> = { LyricsStore.fetch(client.api, it) },
    private val lookupLyrics: suspend (JellyfinItem) -> List<LyricLine> = { Lrclib.lookup(it) }
) : MusicSource {

    override val id: String = "jellyfin"

    override val displayName: String = "Jellyfin"

    override val hasPlayHistory: Boolean = true
    override val playHistoryItemType: String = "Audio"

    private val bios = ConcurrentHashMap<String, String>()
    private val serverLyrics = ConcurrentHashMap<String, List<LyricLine>>()
    private val webLyricsFound = ConcurrentHashMap<String, Boolean>()

    override suspend fun artistBio(artistId: String): String? = bio(artistId)

    override suspend fun albumBio(itemId: String): String? = bio(itemId)

    private suspend fun bio(itemId: String): String? {
        if (!bios.containsKey(itemId)) item(itemId)
        return bios[itemId]?.takeIf { it.isNotBlank() }
    }

    internal suspend fun jellyfinLyrics(itemId: String): List<LyricLine> =
        serverLyrics[itemId] ?: fetchLyrics(itemId).also {
            if (it.isNotEmpty()) serverLyrics[itemId] = it
        }

    fun cachedWebLyricsFound(itemId: String): Boolean? = webLyricsFound[itemId]

    override suspend fun lyricsTimed(itemId: String): List<TimedLyricLine>? {
        webLyricsFound.remove(itemId)
        val local = jellyfinLyrics(itemId)
        val lines = if (local.isNotEmpty() || !Prefs.lyricsWebFallback) local else {
            val track = item(itemId) ?: return null
            lookupLyrics(track).also { webLyricsFound[itemId] = it.isNotEmpty() }
        }
        return lines.filter { it.isSynced }.map { TimedLyricLine(it.startMs, it.text, words = it.words, voice = it.voice) }
            .takeIf { it.isNotEmpty() }
    }

    // ---- Streaming ---------------------------------------------------------

    override fun streamUri(itemId: String, container: String?, serverUrl: String?): String {
        val host = serverUrl ?: client.auth.session?.serverUrl ?: ""
        return AuthStore.audioStreamUrl(host, itemId, container)
    }

    override suspend fun streamTarget(
        itemId: String,
        container: String?,
        serverUrl: String?
    ): PlaybackTarget = PlaybackTarget(
        url = streamUri(itemId, container, serverUrl),
        // Read the session per call: the token that belongs to the session in
        // effect right now, not the one present when the queue was built.
        headers = mapOf(
            HEADER_AUTHORIZATION to AuthStore.authHeader(
                client.auth.session,
                client.deviceId,
                APP_NAME,
                APP_VERSION
            )
        )
    )

    // ---- Browse ------------------------------------------------------------

    override fun cached(prefix: String): List<JellyfinItem>? = media.cached(prefix)

    override fun cache(prefix: String, items: List<JellyfinItem>) = media.cache(prefix, items)

    override suspend fun browse(query: LibraryFilter.ItemsQuery, cachePrefix: String?): ItemsResponse =
        media.browse(query, cachePrefix)

    override suspend fun recentlyPlayed(limit: Int): List<JellyfinItem> = media.recentlyPlayed(limit)

    override suspend fun recentArtists(limit: Int): List<JellyfinItem> = media.recentArtists(limit)

    override suspend fun artists(
        sortBy: String,
        sortOrder: String,
        limit: Int,
        startIndex: Int
    ): ItemsResponse = withContext(Dispatchers.IO) {
        val uid = client.auth.session?.userId ?: return@withContext ItemsResponse()
        LibraryFilter.queryAlbumArtists(client.api, uid, sortBy, sortOrder, limit, startIndex)
    }

    override fun cachedPage(key: String): PagedLibrary.Page? = media.cachedPage(key)

    override fun storePage(key: String, items: List<JellyfinItem>, total: Int) =
        media.storePage(key, items, total)

    override suspend fun item(itemId: String): JellyfinItem? = media.item(itemId)?.also { item ->
        bios[itemId] = item.overview?.let {
            HtmlCompat.fromHtml(it, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()
        }.orEmpty()
    }

    override suspend fun artistAlbums(artistId: String, limit: Int): List<JellyfinItem> =
        media.artistAlbums(artistId, limit)

    override suspend fun artistTopTracks(artistId: String, limit: Int): List<JellyfinItem> =
        media.artistTopTracks(artistId, limit)

    override suspend fun similarArtists(artistId: String, limit: Int): List<JellyfinItem> =
        media.similarArtists(artistId, limit)

    override suspend fun search(term: String, limit: Int): ItemsResponse = media.search(term, limit)

    override suspend fun genres(): List<JellyfinItem> = media.genres()

    override suspend fun atmosAlbums(
        sortBy: String,
        desc: Boolean,
        favoritesOnly: Boolean,
        startIndex: Int,
        limit: Int
    ): ItemsResponse = media.atmosAlbums(sortBy, desc, favoritesOnly, startIndex, limit)

    override suspend fun atmosTracks(
        sortBy: String,
        desc: Boolean,
        favoritesOnly: Boolean,
        startIndex: Int,
        limit: Int
    ): ItemsResponse = media.atmosTracks(sortBy, desc, favoritesOnly, startIndex, limit)

    override suspend fun atmosAlbumIds(): Set<String> = media.atmosAlbumIds()

    override suspend fun atmosCatalog(): AtmosCatalog.Catalog = media.atmosCatalog()

    override suspend fun refreshLibraryList(): List<LibraryRef> = media.refreshLibraryList()

    override fun knownLibraries(): List<LibraryRef> = media.knownLibraries()

    override suspend fun refreshAll() {
        clearCaches()
        media.refreshAll()
    }

    override fun clearCaches() {
        bios.clear()
        serverLyrics.clear()
        webLyricsFound.clear()
        media.clearCaches()
    }

    private companion object {
        /** Standard Authorization scheme the server expects (10.10+). */
        const val HEADER_AUTHORIZATION = "Authorization"
    }
}
