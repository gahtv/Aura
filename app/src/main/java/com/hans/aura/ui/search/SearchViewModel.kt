package com.hans.aura.ui.search

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hans.aura.data.JellyfinItem
import com.hans.aura.data.Prefs
import com.hans.aura.data.SourceQueries
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AURA_SEARCH"
private const val DEBOUNCE_MS = 220L

data class SearchUiState(
    val query: String = "",
    val tracks: List<JellyfinItem>? = null,
    val albums: List<JellyfinItem>? = null,
    val artists: List<JellyfinItem>? = null,
    val searching: Boolean = false,
    val recentSearches: List<String> = emptyList(),
    val error: String? = null,
    /** Bumped on every submit so ENTER re-runs a search whose query did not change. */
    val submitTick: Int = 0
) {
    val allEmpty: Boolean
        get() = tracks.isNullOrEmpty() && albums.isNullOrEmpty() && artists.isNullOrEmpty()
}

/**
 * Search execution and recent-search history.
 *
 * ONE getItems query (`Audio,MusicAlbum,MusicArtist,Playlist`) split client-side
 * by type — the three shelves are rendered stacked on one page, never as tabs.
 * Typing debounces; ENTER/resubmit runs immediately.
 */
class SearchViewModel : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState(recentSearches = Prefs.recentSearches()))
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    fun onQueryChange(value: String) {
        if (value == _state.value.query) return
        _state.update { it.copy(query = value, error = null) }
        scheduleSearch(immediate = false)
    }

    /** Submit [raw]: record history, log the AURA_SEARCH hook, re-run. */
    fun submit() {
        val q = _state.value.query.trim()
        if (q.length >= 2) {
            _state.update { it.copy(recentSearches = Prefs.addRecentSearch(q)) }
        }
        val recent = _state.value.recentSearches.size
        Log.d(TAG, "query=$q recent=$recent")
        _state.update { it.copy(searching = q.length >= 2) }
        scheduleSearch(immediate = true)
    }

    fun retry() = scheduleSearch(immediate = true)

    fun removeRecent(term: String) {
        _state.update { it.copy(recentSearches = Prefs.removeRecentSearch(term)) }
    }

    private fun scheduleSearch(immediate: Boolean) {
        searchJob?.cancel()
        val query = _state.value.query.trim()
        if (query.length < 2) {
            _state.update {
                it.copy(tracks = emptyList(), albums = emptyList(), artists = emptyList(), searching = false, error = null)
            }
            return
        }
        searchJob = viewModelScope.launch {
            if (!immediate) delay(DEBOUNCE_MS)
            runSearch(query)
        }
    }

    private suspend fun runSearch(term: String) {
        _state.update { it.copy(searching = true) }
        runCatching {
            withContext(Dispatchers.IO) {
                // Search is a browse query: hidden libraries are not queried.
                SourceQueries.items(
                    searchTerm = term, recursive = true,
                    includeItemTypes = "Audio,MusicAlbum,MusicArtist,Playlist",
                    limit = 50
                )
            }
        }.onSuccess { result ->
            _state.update {
                it.copy(
                    tracks = result.items.filter { item -> item.type == "Audio" },
                    albums = result.items.filter { item -> item.type == "MusicAlbum" || item.type == "Playlist" },
                    artists = result.items.filter { item -> item.type == "MusicArtist" },
                    searching = false,
                    error = null
                )
            }
        }.onFailure { e ->
            Log.w(TAG, "search term=\"$term\" failed: ${e.message}")
            _state.update {
                it.copy(
                    tracks = emptyList(), albums = emptyList(), artists = emptyList(),
                    searching = false,
                    error = e.message ?: "Couldn't load from server"
                )
            }
        }
    }
}
