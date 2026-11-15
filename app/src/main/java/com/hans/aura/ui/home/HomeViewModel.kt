package com.hans.aura.ui.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hans.aura.AuraApp
import com.hans.aura.data.AtmosCatalog
import com.hans.aura.data.JellyfinItem
import com.hans.aura.data.LibraryCache
import com.hans.aura.data.LibraryFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AURA_HOME"

/** The six Home shelves. `null` = not loaded yet, empty list = loaded empty. */
data class HomeUiState(
    val recentlyPlayed: List<JellyfinItem>? = null,
    val recentAlbums: List<JellyfinItem>? = null,
    val recentArtists: List<JellyfinItem>? = null,
    val atmosAlbums: List<JellyfinItem>? = null,
    val artists: List<JellyfinItem>? = null,
    val playlists: List<JellyfinItem>? = null,
    val error: String? = null
) {
    /** Nothing at all loaded — the only state where a failure surface helps. */
    val allShelvesEmpty: Boolean
        get() = listOf(recentlyPlayed, recentAlbums, recentArtists, atmosAlbums, artists, playlists)
            .all { it.isNullOrEmpty() }

    val fullyCached: Boolean
        get() = recentlyPlayed != null && recentAlbums != null && recentArtists != null &&
            atmosAlbums != null && artists != null && playlists != null
}

/**
 * Home's six shelves: 24h LibraryCache reads/writes plus the queries that fill
 * them. Keeping this out of the composable means the shelves survive a visit to
 * a detail screen and BACK without a refetch.
 */
class HomeViewModel(private val app: AuraApp) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private var loadJob: Job? = null
    // AppRoot's refreshKey starts at 0; the first LaunchedEffect(0) must not
    // double-load what init already fetched.
    private var refreshKeySeen = 0

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
        _state.value = HomeUiState(
            recentlyPlayed = LibraryCache.get("home_recent:$uid"),
            recentAlbums = LibraryCache.get("home_new:$uid"),
            recentArtists = LibraryCache.get("home_recent_artists:$uid"),
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

    private suspend fun fetchMissing(uid: String) {
        val api = app.jellyfin.api
        // Browse queries go through LibraryFilter: hidden libraries are
        // never fetched, so their items cannot land in LibraryCache.
        if (_state.value.recentlyPlayed == null) {
            val items = LibraryFilter.queryItems(
                api, uid,
                LibraryFilter.ItemsQuery(
                    includeItemTypes = "Audio", recursive = true,
                    sortBy = "DatePlayed", sortOrder = "Descending", limit = 20
                )
            ).items
            LibraryCache.put("home_recent:$uid", items)
            _state.update { it.copy(recentlyPlayed = items) }
        }
        if (_state.value.recentAlbums == null) {
            val items = LibraryFilter.queryItems(
                api, uid,
                LibraryFilter.ItemsQuery(
                    includeItemTypes = "MusicAlbum", recursive = true,
                    sortBy = "DateCreated", sortOrder = "Descending", limit = 20
                )
            ).items
            LibraryCache.put("home_new:$uid", items)
            _state.update { it.copy(recentAlbums = items) }
        }
        if (_state.value.recentArtists == null) {
            val items = LibraryFilter.queryItems(
                api, uid,
                LibraryFilter.ItemsQuery(
                    includeItemTypes = "MusicArtist", recursive = true,
                    sortBy = "DatePlayed", sortOrder = "Descending", limit = 20
                )
            ).items
            LibraryCache.put("home_recent_artists:$uid", items)
            _state.update { it.copy(recentArtists = items) }
        }
        if (_state.value.atmosAlbums == null) {
            // Reuse the session-cached AtmosCatalog — never a second
            // whole-library scan. Its ids are matched against the already
            // fetched "Recently added" page first (zero extra queries); only
            // when that page holds no Atmos album do we page the album list.
            val atmosIds = runCatching { AtmosCatalog.albumIds(api, uid) }.getOrDefault(emptySet())
            var picks = _state.value.recentAlbums.orEmpty().filter { it.id in atmosIds }.take(20)
            if (picks.isEmpty() && atmosIds.isNotEmpty()) {
                picks = LibraryFilter.queryItems(
                    api, uid,
                    LibraryFilter.ItemsQuery(
                        includeItemTypes = "MusicAlbum", recursive = true,
                        sortBy = "DateCreated", sortOrder = "Descending", limit = 120
                    )
                ).items.filter { it.id in atmosIds }.take(20)
            }
            LibraryCache.put("home_atmos:$uid", picks)
            _state.update { it.copy(atmosAlbums = picks) }
        }
        if (_state.value.artists == null) {
            val items = LibraryFilter.queryAlbumArtists(api, uid, limit = 100).items
            LibraryCache.put("home_artists:$uid", items)
            _state.update { it.copy(artists = items) }
        }
        if (_state.value.playlists == null) {
            val items = api.getItems(
                userId = uid, includeItemTypes = "Playlist", recursive = true,
                sortBy = "SortName", limit = 50
            ).items
            LibraryCache.put("home_playlists:$uid", items)
            _state.update { it.copy(playlists = items) }
        }
    }
}
