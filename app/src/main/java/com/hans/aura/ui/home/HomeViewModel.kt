package com.hans.aura.ui.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hans.aura.AuraApp
import com.hans.aura.data.JellyfinItem
import com.hans.aura.data.LibraryCache
import com.hans.aura.data.LibraryFilter
import com.hans.aura.data.MusicSources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.hans.aura.data.SourceQueries

private const val TAG = "AURA_HOME"

/**
 * Home's shelves. `null` = not loaded yet, empty list = loaded empty (the shelf hides).
 *
 * Every shelf is answered by the installed MusicSource, so a backend that has no
 * playlists simply reports none and that shelf never appears.
 */
data class HomeUiState(
    val recentlyPlayed: List<JellyfinItem>? = null,
    val recentArtists: List<JellyfinItem>? = null,
    val recentAlbums: List<JellyfinItem>? = null,
    val atmosAlbums: List<JellyfinItem>? = null,
    val artists: List<JellyfinItem>? = null,
    val playlists: List<JellyfinItem>? = null,
    val error: String? = null,
    /** Only a backend with play history has these shelves at all. */
    val historyShelves: Boolean = false
) {
    /** Nothing at all loaded — the only state where a failure surface helps. */
    val allShelvesEmpty: Boolean
        get() = listOf(recentlyPlayed, recentArtists, recentAlbums, atmosAlbums, artists, playlists)
            .all { it.isNullOrEmpty() }

    val fullyCached: Boolean
        get() = recentAlbums != null && atmosAlbums != null && artists != null && playlists != null &&
            (!historyShelves || (recentlyPlayed != null && recentArtists != null))
}

/**
 * Home's shelves: 24h LibraryCache reads/writes plus the queries that fill
 * them. Keeping this out of the composable means the shelves survive a visit to
 * a detail screen and BACK without a refetch.
 *
 * The queries go through [SourceQueries] rather than the server client, so the
 * same shelves work on any installed [com.hans.aura.data.MusicSource]. A shelf
 * the source cannot answer comes back empty instead of failing the screen.
 */
class HomeViewModel(private val app: AuraApp) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private var loadJob: Job? = null
    // AppRoot's refreshKey starts at 0; the first LaunchedEffect(0) must not
    // double-load what init already fetched.
    private var refreshKeySeen = 0

    // Cache-key scope. The user id exists on every backend (non-Jellyfin builds
    // install a synthetic session), so the keys stay as they were.
    private val userId: String get() = app.jellyfin.auth.session?.userId ?: ""

    init {
        load()
    }

    /** Settings > Refresh library clears LibraryCache, then bumps this. */
    fun onRefreshKey(key: Int) {
        if (key == refreshKeySeen) return
        refreshKeySeen = key
        load()
    }

    fun retry() = load()

    private fun load() {
        loadJob?.cancel()
        val uid = userId
        // Session cache first: a fully cached Home does no network at all.
        val history = MusicSources.current?.hasPlayHistory == true
        _state.value = HomeUiState(
            historyShelves = history,
            recentlyPlayed = if (history) LibraryCache.get("home_recent:$uid") else null,
            recentArtists = if (history) LibraryCache.get("home_recent_artists:$uid") else null,
            recentAlbums = LibraryCache.get("home_new:$uid"),
            atmosAlbums = LibraryCache.get("home_atmos:$uid"),
            artists = LibraryCache.get("home_artists:$uid"),
            playlists = LibraryCache.get("home_playlists:$uid")
        )
        if (_state.value.fullyCached) return
        loadJob = viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { fetchMissing(uid) }
            }.onFailure { e ->
                Log.w(TAG, "shelf load failed: ${e.message}")
                _state.update { it.copy(error = e.message ?: "Couldn't load from server") }
            }
        }
    }

    /**
     * One shelf's query. A backend that cannot answer it (no playlists, no
     * Atmos, an index without the field) yields an EMPTY shelf, never a failure
     * that would take the rest of Home down with it.
     */
    private suspend fun shelf(name: String, query: suspend () -> List<JellyfinItem>): List<JellyfinItem> =
        runCatching { query() }
            .onFailure { e ->
                Log.w(TAG, "shelf \"$name\" unavailable: ${e.message}")
                // Remembered only so an all-empty Home can explain itself; a
                // Home with other shelves loaded never shows this.
                _state.update { it.copy(error = it.error ?: (e.message ?: "Couldn't load from server")) }
            }
            .getOrDefault(emptyList())

    private suspend fun fetchMissing(uid: String) {
        // Browse queries go through SourceQueries: the installed source decides
        // how (or whether) to answer, and hidden libraries still apply on Jellyfin.
        if (_state.value.historyShelves && _state.value.recentlyPlayed == null) {
            val items = shelf("recently played") {
                // Jellyfin records plays per track; the index keeps album-level history, so
                // the same shelf is built from albums there.
                SourceQueries.items(LibraryFilter.ItemsQuery(
                        includeItemTypes = MusicSources.current?.playHistoryItemType ?: "Audio",
                        recursive = true,
                        sortBy = "DatePlayed", sortOrder = "Descending", limit = 20
                    )
                ).items
            }
            LibraryCache.put("home_recent:$uid", items)
            _state.update { it.copy(recentlyPlayed = items) }
        }
        // A per-artist play history is a Jellyfin concept; an album-level backend has none,
        // and asking anyway would list every artist it knows.
        val artistHistory = _state.value.historyShelves &&
            (MusicSources.current?.playHistoryItemType ?: "Audio") == "Audio"
        if (artistHistory && _state.value.recentArtists == null) {
            val items = shelf("recently played artists") {
                SourceQueries.items(LibraryFilter.ItemsQuery(
                        includeItemTypes = "MusicArtist", recursive = true,
                        sortBy = "DatePlayed", sortOrder = "Descending", limit = 20
                    )
                ).items
            }
            LibraryCache.put("home_recent_artists:$uid", items)
            _state.update { it.copy(recentArtists = items) }
        }
        if (_state.value.recentAlbums == null) {
            val items = shelf("recently added") {
                SourceQueries.items(LibraryFilter.ItemsQuery(
                        includeItemTypes = "MusicAlbum", recursive = true,
                        sortBy = "DateCreated", sortOrder = "Descending", limit = 20
                    )
                ).items
            }
            LibraryCache.put("home_new:$uid", items)
            _state.update { it.copy(recentAlbums = items) }
        }
        if (_state.value.atmosAlbums == null) {
            val items = shelf("atmos") {
                // Reuse the session-cached AtmosCatalog — never a second
                // whole-library scan. Its ids are matched against the already
                // fetched "Recently added" page first (zero extra queries); only
                // when that page holds no Atmos album do we page the album list.
                val atmosIds = runCatching { SourceQueries.atmosAlbumIds() }.getOrDefault(emptySet())
                if (atmosIds.isEmpty()) {
                    emptyList()
                } else {
                    _state.value.recentAlbums.orEmpty().filter { it.id in atmosIds }.take(20)
                        .ifEmpty {
                            SourceQueries.items(LibraryFilter.ItemsQuery(
                                    includeItemTypes = "MusicAlbum", recursive = true,
                                    sortBy = "DateCreated", sortOrder = "Descending", limit = 120
                                )
                            ).items.filter { it.id in atmosIds }.take(20)
                        }
                }
            }
            LibraryCache.put("home_atmos:$uid", items)
            _state.update { it.copy(atmosAlbums = items) }
        }
        if (_state.value.artists == null) {
            val items = shelf("artists") { SourceQueries.artists(limit = 100).items }
            LibraryCache.put("home_artists:$uid", items)
            _state.update { it.copy(artists = items) }
        }
        if (_state.value.playlists == null) {
            // Empty on a source without playlists: the shelf is then not drawn.
            val items = shelf("playlists") {
                SourceQueries.items(
                    includeItemTypes = "Playlist", recursive = true,
                    sortBy = "SortName", limit = 50
                ).items
            }
            LibraryCache.put("home_playlists:$uid", items)
            _state.update { it.copy(playlists = items) }
        }
    }
}
