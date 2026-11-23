package com.hans.aura.ui.collection

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hans.aura.AuraApp
import com.hans.aura.data.SourceQueries
import com.hans.aura.model.Album
import com.hans.aura.model.Artist
import com.hans.aura.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AURA_ARTIST_VM"

@Immutable
data class ArtistUiState(
    val fetchedArtist: Artist? = null,
    val overview: String? = null,
    val albums: List<Album>? = null,
    val topTracks: List<Track>? = null,
    val similar: List<Artist>? = null,
    val atmosAlbumIds: Set<String> = emptySet(),
    val contentLoading: Boolean = true,
    val atmosLoading: Boolean = true,
    val atmosView: Boolean = false,
    val error: String? = null
) {
    fun shown(fallback: Artist): Artist = fetchedArtist ?: fallback

    val atmosOnly: Boolean
        get() = atmosView || com.hans.aura.data.AtmosFilter.on

    val visibleAlbums: List<Album>
        get() = when {
            albums == null -> emptyList()
            atmosOnly -> albums.filter { it.id in atmosAlbumIds }
            else -> albums
        }

    val visibleTopTracks: List<Track>
        get() {
            val deduped = topTracks?.distinctBy { it.name.lowercase() to it.albumId } ?: return emptyList()
            return if (atmosOnly) {
                deduped.filter { it.albumId?.let { id -> id in atmosAlbumIds } == true }
            } else {
                deduped
            }
        }

    val loading: Boolean get() = albums == null && topTracks == null

    val hasContent: Boolean
        get() = !albums.isNullOrEmpty() || !topTracks.isNullOrEmpty() || !similar.isNullOrEmpty()

    val focusContentSettled: Boolean
        get() = !contentLoading && (!atmosOnly || !atmosLoading)
}

class ArtistViewModel(
    private val app: AuraApp,
    private val artistId: String
) : ViewModel() {

    private val _state = MutableStateFlow(ArtistUiState())
    val state: StateFlow<ArtistUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            val albums = SourceQueries.cachedAlbums(DetailPresenter.artistAlbumsKey(artistId))
            val topTracks = SourceQueries.cachedTracks(DetailPresenter.artistTopTracksKey(artistId))
            val similar = SourceQueries.cachedArtists(DetailPresenter.artistSimilarKey(artistId))
            _state.update { it.copy(albums = albums, topTracks = topTracks, similar = similar) }
            load(force = false)
            scanAtmos()
        }
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
        _state.update { it.copy(contentLoading = true) }
        loadJob = viewModelScope.launch {
            DetailPresenter.hydrateItem(artistId)?.let { full ->
                _state.update {
                    it.copy(
                        fetchedArtist = full as? Artist,
                        overview = DetailPresenter.overview((full as? Artist)?.overview)
                    )
                }
            }
            runCatching {
                if (force || _state.value.similar == null) {
                    DetailPresenter.loadSimilarArtists(artistId).also {
                        _state.update { s -> s.copy(similar = it) }
                    }
                }
                if (force || _state.value.albums == null) {
                    DetailPresenter.loadArtistAlbums(artistId).also {
                        _state.update { s -> s.copy(albums = it) }
                    }
                }
                if (force || _state.value.topTracks == null) {
                    DetailPresenter.loadArtistTopTracks(artistId).also {
                        _state.update { s -> s.copy(topTracks = it) }
                    }
                }
            }.onFailure { e ->
                Log.w(TAG, "artist=$artistId load failed: ${e.message}")
                if (!_state.value.hasContent) {
                    _state.update { it.copy(error = e.message ?: "Couldn't load from server") }
                }
            }
        }.also { job ->
            job.invokeOnCompletion { cause ->
                if (cause !is CancellationException) _state.update { it.copy(contentLoading = false) }
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
            _state.update { it.copy(atmosLoading = false) }
        }
    }
}
