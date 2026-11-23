package com.hans.aura.ui.collection

import android.util.Log
import com.hans.aura.data.SourceQueries
import com.hans.aura.model.Album
import com.hans.aura.model.Artist
import com.hans.aura.model.CollectionItem
import com.hans.aura.model.MediaItem
import com.hans.aura.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shared hydration for the two detail screens (album/playlist and artist).
 */
object DetailPresenter {

    private const val TAG = "AURA_DETAIL"

    fun overview(value: String?): String? = value?.takeIf { it.isNotBlank() }

    fun collectionCacheKey(itemId: String): String = "col:$itemId"

    fun artistAlbumsKey(artistId: String): String = "artist_albums:$artistId"

    fun artistTopTracksKey(artistId: String): String = "artist_top:$artistId"

    fun artistSimilarKey(artistId: String): String = "artist_similar:$artistId"

    suspend fun hydrateItem(itemId: String): MediaItem? = withContext(Dispatchers.IO) {
        runCatching { SourceQueries.item(itemId) }
            .onFailure { Log.w(TAG, "item=$itemId hydrate failed: ${it.message}") }
            .getOrNull()
    }

    suspend fun loadCollectionTracks(collection: CollectionItem): List<Track> = withContext(Dispatchers.IO) {
        SourceQueries.collectionTracks(collection)
    }

    suspend fun loadArtistAlbums(artistId: String): List<Album> = withContext(Dispatchers.IO) {
        SourceQueries.artistAlbums(artistId, cacheKey = artistAlbumsKey(artistId))
    }

    suspend fun loadArtistTopTracks(artistId: String): List<Track> = withContext(Dispatchers.IO) {
        SourceQueries.artistTopTracks(artistId, cacheKey = artistTopTracksKey(artistId))
    }

    suspend fun loadSimilarArtists(artistId: String, limit: Int = 20): List<Artist> = withContext(Dispatchers.IO) {
        runCatching { SourceQueries.similar(artistId, limit, cacheKey = artistSimilarKey(artistId)) }
            .onSuccess { Log.i("AURA_SIMILAR", "artist=$artistId n=${it.size}") }
            .onFailure { Log.w("AURA_SIMILAR", "artist=$artistId failed: ${it.message}") }
            .getOrDefault(emptyList())
    }
}
