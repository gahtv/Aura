package com.hans.aura.ui.genres

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hans.aura.AuraApp
import com.hans.aura.data.AtmosFilter
import com.hans.aura.data.LibraryFilter
import com.hans.aura.data.SourceQueries
import com.hans.aura.model.Album
import com.hans.aura.model.Genre
import com.hans.aura.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AURA_GENRES_VM"

@Immutable
data class GenresUiState(
    val genres: List<Genre>? = null,
    val error: String? = null
)

/** Genre grid backed by the repository scan of genres actually carried by tracks. */
class GenresViewModel(private val app: AuraApp) : ViewModel() {

    private val _state = MutableStateFlow(GenresUiState())
    val state: StateFlow<GenresUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        load()
    }

    fun retry() = load()

    private fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { SourceQueries.genres() }
            }.onSuccess { genres ->
                _state.value = GenresUiState(genres = genres)
            }.onFailure { e ->
                Log.w(TAG, "genre scan failed: ${e.message}")
                _state.update { it.copy(error = e.message ?: "Couldn't load from server") }
            }
        }
    }
}

@Immutable
data class GenreAlbumsUiState(
    val albums: List<Album>? = null,
    val tracks: List<Track>? = null,
    val error: String? = null
) {
    val loading: Boolean get() = albums == null && tracks == null

    val visibleAlbums: List<Album>
        get() = if (!AtmosFilter.on) albums.orEmpty() else albums.orEmpty().filter { it.id in AtmosFilter.albumIds() }

    val visibleTracks: List<Track>
        get() = if (!AtmosFilter.on) tracks.orEmpty() else tracks.orEmpty().filter { it.id in AtmosFilter.trackIds() }
}

/** One genre page: its albums grid, with the full track list as the fallback. */
class GenreAlbumsViewModel(
    private val app: AuraApp,
    private val genreName: String
) : ViewModel() {

    private val _state = MutableStateFlow(GenreAlbumsUiState())
    val state: StateFlow<GenreAlbumsUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            val albums = SourceQueries.cachedAlbums("genre_albums:$genreName")
            val tracks = SourceQueries.cachedTracks("genre_tracks:$genreName")
            _state.update { it.copy(albums = albums, tracks = tracks) }
            if (_state.value.albums == null || _state.value.tracks == null) load()
        }
    }

    fun retry() = load()

    private fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            runCatching {
                val needAlbums = _state.value.albums == null
                val needTracks = _state.value.tracks == null
                val fetched = withContext(Dispatchers.IO) {
                    val albums = if (needAlbums) {
                        SourceQueries.albums(LibraryFilter.ItemsQuery(
                            includeItemTypes = "MusicAlbum",
                            genres = genreName,
                            recursive = true,
                            sortBy = "SortName",
                            sortOrder = "Ascending",
                            limit = 200
                        ), cacheKey = "genre_albums:$genreName").items
                    } else null
                    val tracks = if (needTracks) {
                        SourceQueries.tracks(LibraryFilter.ItemsQuery(
                            includeItemTypes = "Audio",
                            genres = genreName,
                            recursive = true,
                            sortBy = "SortName",
                            sortOrder = "Ascending",
                            limit = 100
                        ), cacheKey = "genre_tracks:$genreName").items
                    } else null
                    albums to tracks
                }
                fetched.first?.let { albums -> _state.update { it.copy(albums = albums) } }
                fetched.second?.let { tracks -> _state.update { it.copy(tracks = tracks) } }
            }.onFailure { e ->
                Log.w(TAG, "genre=$genreName load failed: ${e.message}")
                _state.update { it.copy(error = e.message ?: "Couldn't load from server") }
            }
        }
    }
}
