package com.hans.aura.ui.collection

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hans.aura.AuraApp
import com.hans.aura.data.AtmosCatalog
import com.hans.aura.data.JellyfinItem
import com.hans.aura.data.LibraryCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.hans.aura.data.SourceQueries

private const val TAG = "AURA_ARTIST_VM"

/**
 * Artist page: the hydrated artist (stub items carry no ImageTags, so the real
 * item feeds the dynamic palette), albums, deduped top tracks, similar artists
 * and the per-view "Only Atmos" toggle.
 *
 * Albums are NOT deduped (a FLAC copy and its Atmos master are two releases);
 * only top tracks dedupe, keyed by name + album id.
 */
data class ArtistUiState(
    val fetchedArtist: JellyfinItem? = null,
    val overview: String? = null,
    val albums: List<JellyfinItem>? = null,
    val topTracks: List<JellyfinItem>? = null,
    val similar: List<JellyfinItem>? = null,
    val atmosAlbumIds: Set<String> = emptySet(),
    val atmosView: Boolean = false,
    val error: String? = null
) {
    fun shown(fallback: JellyfinItem): JellyfinItem = fetchedArtist ?: fallback

    val atmosOnly: Boolean
        get() = atmosView || com.hans.aura.data.AtmosFilter.on

    /** Albums after the per-view Atmos filter (Settings preference included). */
    val visibleAlbums: List<JellyfinItem>
        get() = when {
            albums == null -> emptyList()
            atmosOnly -> albums.filter { it.id in atmosAlbumIds }
            else -> albums
        }

    /** Top tracks deduped by name + album id, then Atmos-filtered. */
    val visibleTopTracks: List<JellyfinItem>
        get() {
            val deduped = topTracks?.distinctBy { it.name.lowercase() to it.albumId } ?: return emptyList()
            // Atmos tracks are identified by their ALBUM id: the FLAC and the
            // Atmos copy are different albums, so this picks exactly the Atmos
            // performances without a second scan.
            return if (atmosOnly) {
                deduped.filter { it.albumId?.let { id -> id in atmosAlbumIds } == true }
            } else {
                deduped
            }
        }

    val hasContent: Boolean
        get() = !albums.isNullOrEmpty() || !topTracks.isNullOrEmpty() || !similar.isNullOrEmpty()
}

class ArtistViewModel(
    private val app: AuraApp,
    private val artistId: String
) : ViewModel() {

    private val _state = MutableStateFlow(ArtistUiState())
    val state: StateFlow<ArtistUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    private val userId: String get() = app.jellyfin.auth.session?.userId ?: ""

    init {
        _state.update {
            it.copy(
                albums = LibraryCache.get(DetailPresenter.artistAlbumsKey(artistId)),
                topTracks = LibraryCache.get(DetailPresenter.artistTopTracksKey(artistId)),
                similar = LibraryCache.get(DetailPresenter.artistSimilarKey(artistId))
            )
        }
        load(force = false)
        scanAtmos()
    }

    fun retry() = load(force = true)

    fun toggleAtmos() {
        _state.update { it.copy(atmosView = !it.atmosView) }
        Log.d(
            "AURA_ATMOS",
            "view=${if (_state.value.atmosView) "on" else "off"} kind=artist artist=$artistId"
        )
    }

    private fun load(force: Boolean) {
        loadJob?.cancel()
        val uid = userId
        loadJob = viewModelScope.launch {
            DetailPresenter.hydrateItem(app.jellyfin.api, uid, artistId)?.let { full ->
                _state.update {
                    it.copy(fetchedArtist = full, overview = DetailPresenter.overview(full.overview))
                }
            }
            runCatching {
                if (force || _state.value.similar == null) {
                    DetailPresenter.loadSimilarArtists(app.jellyfin.api, uid, artistId).also {
                        LibraryCache.put(DetailPresenter.artistSimilarKey(artistId), it)
                        _state.update { s -> s.copy(similar = it) }
                    }
                }
                if (force || _state.value.albums == null) {
                    DetailPresenter.loadArtistAlbums(app.jellyfin.api, uid, artistId).also {
                        LibraryCache.put(DetailPresenter.artistAlbumsKey(artistId), it)
                        _state.update { s -> s.copy(albums = it) }
                    }
                }
                if (force || _state.value.topTracks == null) {
                    DetailPresenter.loadArtistTopTracks(app.jellyfin.api, uid, artistId).also {
                        LibraryCache.put(DetailPresenter.artistTopTracksKey(artistId), it)
                        _state.update { s -> s.copy(topTracks = it) }
                    }
                }
            }.onFailure { e ->
                Log.w(TAG, "artist=$artistId load failed: ${e.message}")
                if (!_state.value.hasContent) {
                    _state.update { it.copy(error = e.message ?: "Couldn't load from server") }
                }
            }
        }
    }

    private fun scanAtmos() {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { SourceQueries.atmosAlbumIds() }
            }.onSuccess { ids ->
                _state.update { it.copy(atmosAlbumIds = ids) }
                Log.d(
                    "AURA_ARTIST",
                    "state albums=${_state.value.albums?.size ?: -1} atmos=${ids.size}"
                )
            }.onFailure { Log.w(TAG, "atmos scan failed: ${it.message}") }
        }
    }
}
