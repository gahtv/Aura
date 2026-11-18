package com.hans.aura.ui.collection

import android.util.Log
import com.hans.aura.data.JellyfinApi
import com.hans.aura.data.JellyfinItem
import com.hans.aura.data.LibraryFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.hans.aura.data.SourceQueries

/**
 * Shared hydration for the two detail screens (album/playlist and artist).
 *
 * Both screens are entered from stub items (id + name only — "View artist" in a
 * context menu carries no ImageTags), so every load starts with a `getItem`
 * that resolves the real item for the dynamic palette, the bio and the title.
 * The error rule is the same everywhere too: a failure only reaches the screen
 * when there is nothing cached to show, otherwise the stale list stays put.
 */
object DetailPresenter {

    private const val TAG = "AURA_DETAIL"

    /** Trimmed Overview, or null when the server has no bio for the item. */
    fun overview(value: String?): String? = value?.takeIf { it.isNotBlank() }

    fun collectionCacheKey(itemId: String): String = "col:$itemId"

    fun artistAlbumsKey(artistId: String): String = "artist_albums:$artistId"

    fun artistTopTracksKey(artistId: String): String = "artist_top:$artistId"

    fun artistSimilarKey(artistId: String): String = "artist_similar:$artistId"

    /** The full item (art tags, Overview) behind a stub; null on failure. */
    suspend fun hydrateItem(api: JellyfinApi, userId: String, itemId: String): JellyfinItem? =
        withContext(Dispatchers.IO) {
            runCatching { SourceQueries.item(itemId) }
                .onFailure { Log.w(TAG, "item=$itemId hydrate failed: ${it.message}") }
                .getOrNull()
        }

    /** Playlist items in playlist order, or an album's tracks in disc order. */
    suspend fun loadCollectionTracks(
        api: JellyfinApi,
        userId: String,
        itemId: String,
        type: String
    ): List<JellyfinItem> = withContext(Dispatchers.IO) {
        val result = when (type) {
            "Playlist" -> api.getPlaylistItems(playlistId = itemId, userId = userId)
            else -> SourceQueries.items(
                userId = userId, parentId = itemId,
                includeItemTypes = "Audio", sortBy = "ParentIndexNumber,IndexNumber,SortName",
                limit = 500
            )
        }
        result.items
    }

    /** Albums of one artist — NOT deduped: a FLAC copy and the Atmos copy are two. */
    suspend fun loadArtistAlbums(
        api: JellyfinApi,
        userId: String,
        artistId: String
    ): List<JellyfinItem> = withContext(Dispatchers.IO) {
        SourceQueries.items(LibraryFilter.ItemsQuery(
                includeItemTypes = "MusicAlbum",
                albumArtistIds = artistId, recursive = true,
                sortBy = "ProductionYear", sortOrder = "Descending", limit = 100
            )
        ).items
    }

    suspend fun loadArtistTopTracks(
        api: JellyfinApi,
        userId: String,
        artistId: String
    ): List<JellyfinItem> = withContext(Dispatchers.IO) {
        SourceQueries.items(LibraryFilter.ItemsQuery(
                includeItemTypes = "Audio",
                albumArtistIds = artistId, recursive = true,
                sortBy = "PlayCount", sortOrder = "Descending", limit = 10
            )
        ).items
    }

    /**
     * GET /Artists/{itemId}/Similar — the collaboration graph Jellyfin Web's
     * "Similar artists" row uses. Errors are logged and swallowed: the shelf is
     * supplementary and must never take the page down.
     */
    suspend fun loadSimilarArtists(
        api: JellyfinApi,
        userId: String,
        artistId: String,
        limit: Int = 20
    ): List<JellyfinItem> = withContext(Dispatchers.IO) {
        runCatching { SourceQueries.similar(artistId, limit) }
            .onSuccess { Log.i("AURA_SIMILAR", "artist=$artistId n=${it.size}") }
            .onFailure { Log.w("AURA_SIMILAR", "artist=$artistId failed: ${it.message}") }
            .getOrDefault(emptyList())
    }
}
