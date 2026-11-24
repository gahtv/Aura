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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
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
        // Subscribe before deciding whether to trust the cache: a playlist
        // reopened while an earlier edit is still in flight must catch that
        // edit's completion even if it lands right after this point.
        if (collection is Playlist) observePlaylistChanges()
        viewModelScope.launch {
            val pendingEdit = collection is Playlist &&
                app.container.mutations.playlistEditPending(itemId)
            // While an edit is pending the cache still holds the pre-edit
            // list (eviction happens only after the server applied it).
            // Seeding it would let the user act on rows that no longer map to
            // the server list, so show Loading and force a fetch instead.
            val cached = if (pendingEdit) {
                null
            } else {
                SourceQueries.cachedTracks(DetailPresenter.collectionCacheKey(itemId))
            }
            _state.update { it.copy(tracks = cached) }
            if (collection is Playlist) scanAtmos()
            load(force = pendingEdit)
        }
    }

    fun retry() = load(force = true)

    fun refresh() = load(force = true)

    fun removeFromPlaylist(trackId: String, occurrence: Int, playlistItemId: String? = null) {
        val mutations = app.container.mutations
        // Target the exact entry the user clicked when the row carries one:
        // an ordinal computed from a stale list could resolve to a different
        // duplicate server-side. The entry-id path fails harmlessly if the
        // entry is already gone.
        val token = mutations.beginPlaylistEdit(itemId)
        // Session scope, not viewModelScope: popping this playlist's NavEntry
        // clears the ViewModelStore and cancelling the write (or the cache
        // eviction after the server already applied it) leaves a stale list.
        // The state updates are harmless once cleared; load() is a no-op then.
        mutations.scope.launch {
            try {
                runCatching {
                    SourceQueries.removeFromPlaylist(
                        playlistId = itemId,
                        itemId = trackId,
                        occurrence = occurrence,
                        entryId = playlistItemId
                    )
                }
                    .onSuccess { load(force = true) }
                    .onFailure {
                        Log.w(
                            TAG,
                            "remove item=$trackId occurrence=$occurrence entry=$playlistItemId " +
                                "from playlist=$itemId failed: ${it.message}"
                        )
                        _state.update { state ->
                            state.copy(error = "Couldn't remove from playlist: ${it.message}")
                        }
                    }
            } finally {
                // Even a cancelled (old-profile) mutation must end its
                // pending marker; reset() has already invalidated its token in
                // that case, so this cannot notify the new session.
                mutations.finishPlaylistEdit(itemId, token)
            }
        }
    }

    /** Refetches this playlist whenever a pending edit of it completes. */
    private fun observePlaylistChanges() {
        viewModelScope.launch {
            app.container.mutations.observePlaylistChanges()
                .filter { it == itemId }
                .collect { load(force = true) }
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
