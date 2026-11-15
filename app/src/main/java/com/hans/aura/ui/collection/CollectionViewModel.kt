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

private const val TAG = "AURA_COL"

/**
 * Album or playlist detail: the tracklist, the hydrated item (art + bio) and
 * the playlist mutations that change the list in place.
 */
data class CollectionUiState(
    val tracks: List<JellyfinItem>? = null,
    val fullItem: JellyfinItem? = null,
    val overview: String? = null,
    val error: String? = null,
    /** Album ids that carry Atmos, for the per-row chips on playlist pages. */
    val atmosAlbumIds: Set<String> = emptySet()
) {
    /** The item to render: the hydrated one when it arrived, else the stub. */
    fun shown(fallback: JellyfinItem): JellyfinItem = fullItem ?: fallback
}

class CollectionViewModel(
    private val app: AuraApp,
    private val itemId: String,
    private val itemType: String
) : ViewModel() {

    private val _state = MutableStateFlow(CollectionUiState())
    val state: StateFlow<CollectionUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    private val userId: String get() = app.jellyfin.auth.session?.userId ?: ""

    init {
        // Cached tracklist first, so a BACK return paints instantly.
        _state.update {
            it.copy(tracks = LibraryCache.get(DetailPresenter.collectionCacheKey(itemId)))
        }
        if (itemType == "Playlist") scanAtmos()
        load(force = false)
    }

    /** Retry from the ErrorPane: ignore the cache and fetch again. */
    fun retry() = load(force = true)

    /** A playlist mutation landed (remove / reorder): refetch the list. */
    fun refresh() = load(force = true)

    fun removeFromPlaylist(trackId: String) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { app.jellyfin.api.removeFromPlaylist(itemId, trackId) }
            }.onSuccess {
                LibraryCache.evict(DetailPresenter.collectionCacheKey(itemId))
                load(force = true)
            }.onFailure {
                Log.w(TAG, "remove item=$trackId from playlist=$itemId failed: ${it.message}")
            }
        }
    }

    private fun load(force: Boolean) {
        loadJob?.cancel()
        val uid = userId
        loadJob = viewModelScope.launch {
            // Stub hydration: the palette and the bio need the real ImageTags.
            DetailPresenter.hydrateItem(app.jellyfin.api, uid, itemId)?.let { full ->
                _state.update {
                    it.copy(fullItem = full, overview = DetailPresenter.overview(full.overview))
                }
            }
            if (!force && _state.value.tracks != null) return@launch
            runCatching {
                DetailPresenter.loadCollectionTracks(app.jellyfin.api, uid, itemId, itemType)
            }.onSuccess { tracks ->
                LibraryCache.put(DetailPresenter.collectionCacheKey(itemId), tracks)
                _state.update { it.copy(tracks = tracks, error = null) }
            }.onFailure { e ->
                Log.w(TAG, "tracks item=$itemId failed: ${e.message}")
                // A failed refresh must not blank a list we already have.
                if (_state.value.tracks == null) {
                    _state.update { it.copy(error = e.message ?: "Couldn't load from server") }
                }
            }
        }
    }

    private fun scanAtmos() {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { AtmosCatalog.albumIds(app.jellyfin.api, userId) }
            }.onSuccess { ids -> _state.update { it.copy(atmosAlbumIds = ids) } }
                .onFailure { Log.w(TAG, "atmos scan failed: ${it.message}") }
        }
    }
}
