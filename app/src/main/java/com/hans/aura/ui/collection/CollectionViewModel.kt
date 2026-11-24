package com.hans.aura.ui.collection

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hans.aura.AuraApp
import com.hans.aura.data.SourceQueries
import com.hans.aura.di.PlaylistEditOutcome
import com.hans.aura.model.Album
import com.hans.aura.model.CollectionItem
import com.hans.aura.model.Playlist
import com.hans.aura.model.Track
import kotlinx.coroutines.CancellationException
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
import retrofit2.HttpException

private const val TAG = "AURA_COL"

/** Shown when a removal's response was lost and the list must be re-read. */
private const val UNKNOWN_EDIT_MESSAGE = "Removal may not have completed — refreshing playlist"

@Immutable
data class CollectionUiState(
    val tracks: List<Track>? = null,
    val fullItem: CollectionItem? = null,
    val overview: String? = null,
    val error: String? = null,
    val atmosAlbumIds: Set<String> = emptySet(),
    /** A playlist edit is in flight: removal stays disabled until its refresh lands. */
    val editInFlight: Boolean = false,
    /** Last playlist-edit failure; survives successful reloads, unlike [error]. */
    val editError: String? = null
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
            _state.update { it.copy(tracks = cached, editInFlight = pendingEdit) }
            if (collection is Playlist) scanAtmos()
            load(force = pendingEdit)
        }
    }

    fun retry() = load(force = true)

    fun refresh() = load(force = true)

    fun removeFromPlaylist(trackId: String, occurrence: Int) {
        val mutations = app.container.mutations
        // The displayed list must be post-edit before an ordinal is resolved
        // against it: an edit still in flight belongs to a list the user can
        // no longer see, so refuse instead of racing that edit's refresh.
        if (mutations.playlistEditPending(itemId)) {
            _state.update { it.copy(editError = "Playlist is still updating") }
            return
        }
        val token = mutations.beginPlaylistEdit(itemId)
        _state.update { it.copy(editInFlight = true, editError = null) }
        // Session scope, not viewModelScope: popping this playlist's NavEntry
        // clears the ViewModelStore and cancelling the write (or the cache
        // eviction after the server already applied it) leaves a stale list.
        // The state updates are harmless once cleared; load() is a no-op then.
        mutations.scope.launch {
            // A cancellation still leaves the outcome UNKNOWN: the write may
            // have reached the server. The pending marker below is what tells
            // any surviving screen to reconcile.
            var outcome = PlaylistEditOutcome.UNKNOWN
            try {
                outcome = try {
                    SourceQueries.removeFromPlaylist(itemId, trackId, occurrence)
                    PlaylistEditOutcome.SUCCESS
                } catch (c: CancellationException) {
                    // Keep structured cancellation intact; the finally below
                    // still publishes UNKNOWN.
                    throw c
                } catch (t: Throwable) {
                    val classified = classify(t)
                    Log.w(
                        TAG,
                        "remove item=$trackId occurrence=$occurrence from playlist=$itemId" +
                            " failed ($classified): ${t.message}"
                    )
                    _state.update { state ->
                        state.copy(
                            editError = when (classified) {
                                PlaylistEditOutcome.UNKNOWN -> UNKNOWN_EDIT_MESSAGE
                                else -> "Couldn't remove from playlist: ${t.message}"
                            }
                        )
                    }
                    classified
                }
            } finally {
                // Even a cancelled (old-profile) mutation must end its
                // pending marker; reset() has already invalidated its token in
                // that case, so this cannot notify the new session.
                mutations.finishPlaylistEdit(itemId, token, outcome)
            }
        }
    }

    /**
     * A 4xx means the server refused before committing, and the local
     * precondition failures ([IllegalStateException]/[IllegalArgumentException])
     * are raised before any write, so both leave the visible list valid.
     * Everything else — I/O, timeout, 5xx, a response lost after the write —
     * may have committed and must be reconciled against the server.
     */
    private fun classify(error: Throwable): PlaylistEditOutcome = when {
        error is HttpException ->
            if (error.code() in 400..499) PlaylistEditOutcome.FAILED else PlaylistEditOutcome.UNKNOWN
        error is IllegalArgumentException || error is IllegalStateException -> PlaylistEditOutcome.FAILED
        else -> PlaylistEditOutcome.UNKNOWN
    }

    /**
     * Applies a completed edit.
     *
     * SUCCESS refetches the post-edit order; FAILED changed nothing, so keep
     * its error without reloading (a reload would re-show the pre-edit list as
     * if it were fresh). UNKNOWN may have committed: keep removal disabled and
     * force a reconciling read. [load] clears `editInFlight` only when that
     * read succeeds, so a failed refresh leaves the banner's Retry in charge.
     */
    private fun observePlaylistChanges() {
        viewModelScope.launch {
            app.container.mutations.observePlaylistChanges()
                .filter { it.playlistId == itemId }
                .collect { completed ->
                    when (completed.outcome) {
                        PlaylistEditOutcome.SUCCESS -> load(force = true)
                        PlaylistEditOutcome.FAILED ->
                            _state.update { it.copy(editInFlight = false) }
                        PlaylistEditOutcome.UNKNOWN -> {
                            _state.update {
                                it.copy(
                                    editInFlight = true,
                                    editError = it.editError ?: UNKNOWN_EDIT_MESSAGE
                                )
                            }
                            load(force = true)
                        }
                    }
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
                .onSuccess { tracks ->
                    _state.update {
                        it.copy(
                            tracks = tracks,
                            error = null,
                            // A second edit may have started while this fetch
                            // ran; keep removal disabled until its refresh.
                            editInFlight = app.container.mutations.playlistEditPending(itemId)
                        )
                    }
                }
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
