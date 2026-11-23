package com.hans.aura.ui.collection

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hans.aura.AuraApp
import com.hans.aura.data.SourceQueries
import com.hans.aura.model.Album
import com.hans.aura.model.CollectionItem
import com.hans.aura.model.Playlist
import com.hans.aura.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AURA_COL"

@Immutable
data class CollectionUiState(
    val tracks: List<Track>? = null,
    val fullItem: CollectionItem? = null,
    val overview: String? = null,
    val error: String? = null,
    val atmosAlbumIds: Set<String> = emptySet()
) {
    fun shown(fallback: CollectionItem): CollectionItem = fullItem ?: fallback
}

class CollectionViewModel(
    private val app: AuraApp,
    private val collection: CollectionItem
) : ViewModel() {

    private val itemId = collection.id
    private val _state = MutableStateFlow(CollectionUiState())
    val state: StateFlow<CollectionUiState> = _state.asStateFlow()
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            val tracks = SourceQueries.cachedTracks(DetailPresenter.collectionCacheKey(itemId))
            _state.update { it.copy(tracks = tracks) }
            if (collection is Playlist) scanAtmos()
            load(force = false)
        }
    }

    fun retry() = load(force = true)

    fun refresh() = load(force = true)

    fun removeFromPlaylist(trackId: String, occurrence: Int) {
        viewModelScope.launch {
            runCatching { SourceQueries.removeFromPlaylist(itemId, trackId, occurrence) }
                .onSuccess { load(force = true) }
                .onFailure {
                    Log.w(TAG, "remove item=$trackId occurrence=$occurrence from playlist=$itemId failed: ${it.message}")
                    _state.update { state -> state.copy(error = "Couldn't remove from playlist: ${it.message}") }
                }
        }
    }

    private fun load(force: Boolean) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            DetailPresenter.hydrateItem(itemId)?.let { full ->
                val overview = when (full) {
                    is Album -> full.overview
                    is Playlist -> full.overview
                    else -> null
                }
                _state.update {
                    it.copy(fullItem = full as? CollectionItem, overview = DetailPresenter.overview(overview))
                }
            }
            if (!force && _state.value.tracks != null) return@launch
            runCatching { DetailPresenter.loadCollectionTracks(collection) }
                .onSuccess { tracks -> _state.update { it.copy(tracks = tracks, error = null) } }
                .onFailure { e ->
                    Log.w(TAG, "tracks item=$itemId failed: ${e.message}")
                    if (_state.value.tracks == null) {
                        _state.update { it.copy(error = e.message ?: "Couldn't load from server") }
                    }
                }
        }
    }

    private fun scanAtmos() {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { SourceQueries.atmosAlbumIds() } }
                .onSuccess { ids -> _state.update { it.copy(atmosAlbumIds = ids) } }
                .onFailure { Log.w(TAG, "atmos scan failed: ${it.message}") }
        }
    }
}
