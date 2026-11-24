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
    private val fetchLyrics: suspend (String) -> List<LyricLine>? = { LyricsStore.fetchCacheable(client.api, it) },
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
        serverLyrics[itemId] ?: fetchLyrics(itemId).let { fetched ->
            if (fetched == null) serverLyrics[itemId].orEmpty()
            else serverLyrics.putIfAbsent(itemId, fetched) ?: fetched
        }

    fun cachedWebLyricsFound(itemId: String): Boolean? = webLyricsFound[itemId]

    override val supportsProviderLyrics: Boolean = true

    override suspend fun searchProviderLyrics(itemId: String): ProviderLyricsResult<List<ProviderLyricMatch>> =
        providerLyricsRequest {
            client.api.searchRemoteLyrics(itemId).mapNotNull { result ->
                val lines = LyricsStore.parseResponseOffMain(result.lyrics)
                val preview = lines.asSequence().map { it.text }.filter { it.isNotBlank() }.take(4).toList()
                if (preview.isEmpty()) null
                else ProviderLyricMatch(result.id, result.providerName, lines.any { it.isSynced }, preview)
            }
        }

    override suspend fun saveProviderLyrics(itemId: String, matchId: String): ProviderLyricsResult<Unit> =
        providerLyricsRequest {
            val lines = LyricsStore.parseResponseOffMain(client.api.downloadRemoteLyrics(itemId, matchId))
            // Jellyfin queues a metadata refresh after saving. Use the returned lyrics now.
            if (lines.isEmpty()) serverLyrics.remove(itemId) else serverLyrics[itemId] = lines
            webLyricsFound.remove(itemId)
            Unit
        }

    override suspend fun lyricsTimed(itemId: String): List<TimedLyricLine>? {
        webLyricsFound.remove(itemId)
        val local = jellyfinLyrics(itemId)
        val lines = if (local.isNotEmpty() || !Prefs.lyricsWebFallback) local else {
            val track = item(itemId) ?: return null
            lookupLyrics(track).also { webLyricsFound[itemId] = it.isNotEmpty() }
        }
        return lines.filter { it.isSynced }.map { it.toTimedLyricLine() }
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

    override suspend fun cached(prefix: String): List<JellyfinItem>? = media.rawCached(prefix)

    override fun cache(prefix: String, items: List<JellyfinItem>) = media.rawCache(prefix, items)

    override suspend fun browse(query: LibraryFilter.ItemsQuery, cachePrefix: String?): ItemsResponse =
        media.rawBrowse(query, cachePrefix)

    override suspend fun playlistItems(playlistId: String): List<JellyfinItem> =
        media.rawPlaylistItems(playlistId)

    override val supportsPlaylistEntryRemoval: Boolean = true

    override suspend fun removePlaylistEntry(playlistId: String, entryId: String) =
        media.removePlaylistEntry(playlistId, entryId)

    override suspend fun removeFromPlaylist(playlistId: String, itemId: String, occurrence: Int) =
        media.removeFromPlaylist(playlistId, itemId, occurrence)

    override suspend fun recentlyPlayed(limit: Int): List<JellyfinItem> = media.rawRecentlyPlayed(limit)

    override suspend fun recentArtists(limit: Int): List<JellyfinItem> = media.rawRecentArtists(limit)

    override suspend fun artists(
        sortBy: String,
        sortOrder: String,
        limit: Int,
        startIndex: Int
    ): ItemsResponse = media.rawArtists(sortBy, sortOrder, limit, startIndex)

    override fun cachedPage(key: String): PagedLibrary.Page? = media.rawCachedPage(key)

    override fun storePage(key: String, items: List<JellyfinItem>, total: Int, requiresRefresh: Boolean) =
        media.rawStorePage(key, items, total, requiresRefresh)

    override suspend fun item(itemId: String): JellyfinItem? = media.rawItem(itemId)?.also { item ->
        bios[itemId] = item.overview?.let {
            HtmlCompat.fromHtml(it, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()
        }.orEmpty()
    }

    override suspend fun artistAlbums(artistId: String, limit: Int): List<JellyfinItem> =
        media.rawArtistAlbums(artistId, limit)

    override suspend fun artistTopTracks(artistId: String, limit: Int): List<JellyfinItem> =
        media.rawArtistTopTracks(artistId, limit)

    override suspend fun similarArtists(artistId: String, limit: Int): List<JellyfinItem> =
        media.rawSimilarArtists(artistId, limit)

    override suspend fun search(term: String, limit: Int): ItemsResponse = media.rawSearch(term, limit)

    override suspend fun genres(): List<JellyfinItem> = media.rawGenres()

    override suspend fun atmosAlbums(
        sortBy: String,
        desc: Boolean,
        favoritesOnly: Boolean,
        startIndex: Int,
        limit: Int
    ): ItemsResponse = media.rawAtmosAlbums(sortBy, desc, favoritesOnly, startIndex, limit)

    override suspend fun atmosTracks(
        sortBy: String,
        desc: Boolean,
        favoritesOnly: Boolean,
        startIndex: Int,
        limit: Int
    ): ItemsResponse = media.rawAtmosTracks(sortBy, desc, favoritesOnly, startIndex, limit)

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
