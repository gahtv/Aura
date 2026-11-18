package com.hans.aura.ui.genres

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hans.aura.AuraApp
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
import com.hans.aura.data.SourceQueries

private const val TAG = "AURA_GENRES_VM"

data class GenresUiState(
    val genres: List<JellyfinItem>? = null,
    val error: String? = null
)

/**
 * The genre grid. The list is built from the genre strings the tracks actually
 * carry, NOT from the server's MusicGenre table: that table keeps every genre
 * the library ever saw (264 here, most matching nothing) and `Genres=` compares
 * the FULL string, so half its entries open an empty screen.
 */
class GenresViewModel(private val app: AuraApp) : ViewModel() {

    private val _state = MutableStateFlow(GenresUiState())
    val state: StateFlow<GenresUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    private val userId: String get() = app.jellyfin.auth.session?.userId ?: ""

    init {
        val cached = LibraryCache.get("genres:$userId")
        if (cached != null) {
            _state.value = GenresUiState(genres = cached)
        } else {
            load()
        }
    }

    fun retry() = load()

    private fun load() {
        loadJob?.cancel()
        val uid = userId
        loadJob = viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { scanGenres(app.jellyfin.api, uid) }
            }.onSuccess { genres ->
                LibraryCache.put("genres:$uid", genres)
                _state.value = GenresUiState(genres = genres)
            }.onFailure { e ->
                Log.w(TAG, "genre scan failed: ${e.message}")
                _state.update { it.copy(error = e.message ?: "Couldn't load from server") }
            }
        }
    }
}

data class GenreAlbumsUiState(
    val albums: List<JellyfinItem>? = null,
    val tracks: List<JellyfinItem>? = null,
    val error: String? = null
) {
    val loading: Boolean get() = albums == null && tracks == null

    /** Albums after the "Only Atmos" preference, else the raw list. */
    val visibleAlbums: List<JellyfinItem>
        get() = com.hans.aura.data.AtmosFilter.albums(albums ?: emptyList())

    val visibleTracks: List<JellyfinItem>
        get() = com.hans.aura.data.AtmosFilter.tracks(tracks ?: emptyList())
}

/** One genre page: its albums grid, with the full track list as the fallback. */
class GenreAlbumsViewModel(
    private val app: AuraApp,
    private val genreName: String
) : ViewModel() {

    private val _state = MutableStateFlow(GenreAlbumsUiState())
    val state: StateFlow<GenreAlbumsUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    private val userId: String get() = app.jellyfin.auth.session?.userId ?: ""

    init {
        _state.update {
            it.copy(
                albums = LibraryCache.get("genre_albums:$genreName"),
                tracks = LibraryCache.get("genre_tracks:$genreName")
            )
        }
        if (_state.value.albums == null || _state.value.tracks == null) load()
    }

    fun retry() = load()

    private fun load() {
        loadJob?.cancel()
        val uid = userId
        loadJob = viewModelScope.launch {
            runCatching {
                val needAlbums = _state.value.albums == null
                val needTracks = _state.value.tracks == null
                // Network on IO; the StateFlow is only touched back on Main.
                val fetched = withContext(Dispatchers.IO) {
                    val api = app.jellyfin.api
                    val albums = if (needAlbums) {
                        SourceQueries.items(LibraryFilter.ItemsQuery(
                                includeItemTypes = "MusicAlbum",
                                genres = genreName,
                                recursive = true,
                                sortBy = "SortName",
                                sortOrder = "Ascending",
                                limit = 200
                            )
                        ).items
                    } else null
                    val tracks = if (needTracks) {
                        SourceQueries.items(LibraryFilter.ItemsQuery(
                                includeItemTypes = "Audio",
                                genres = genreName,
                                recursive = true,
                                sortBy = "SortName",
                                sortOrder = "Ascending",
                                limit = 100
                            )
                        ).items
                    } else null
                    albums to tracks
                }
                fetched.first?.let { albums ->
                    LibraryCache.put("genre_albums:$genreName", albums)
                    _state.update { it.copy(albums = albums) }
                }
                fetched.second?.let { tracks ->
                    LibraryCache.put("genre_tracks:$genreName", tracks)
                    _state.update { it.copy(tracks = tracks) }
                }
            }.onFailure { e ->
                Log.w(TAG, "genre=$genreName load failed: ${e.message}")
                _state.update { it.copy(error = e.message ?: "Couldn't load from server") }
            }
        }
    }
}

/**
 * Genres of the MUSIC libraries only.
 *
 * `getItems(IncludeItemTypes=MusicGenre)` is not a "music" item type for
 * LibraryFilter's per-library strategy, so an unscoped query also returns
 * movie/TV genres — "Drama", "Action" and friends would sit in the genre grid.
 * Scanning Audio costs a few pages once per cache TTL and every entry it
 * produces is guaranteed to have music behind it.
 */
private suspend fun scanGenres(api: com.hans.aura.data.JellyfinApi, userId: String): List<JellyfinItem> {
    val names = LinkedHashSet<String>()
    var start = 0
    var total = Int.MAX_VALUE
    var pages = 0
    while (start < total && pages < 12) {
        val result = runCatching {
            SourceQueries.items(LibraryFilter.ItemsQuery(
                    includeItemTypes = "Audio",
                    recursive = true,
                    sortBy = "SortName",
                    sortOrder = "Ascending",
                    limit = 500,
                    startIndex = start,
                    fields = "Genres"
                )
            )
        }.getOrNull() ?: break
        result.items.forEach { item ->
            item.genres.orEmpty().forEach { g ->
                g.trim().takeIf { it.isNotBlank() }?.let(names::add)
            }
        }
        if (result.items.isEmpty()) break
        total = result.totalRecordCount
        start += result.items.size
        pages++
    }
    Log.i("AURA_GENRES", "scanned=$start distinct=${names.size} pages=$pages")
    return names.sortedBy { it.lowercase() }.map { name ->
        // A synthetic item: the grid only needs a stable id and a label, and
        // the real id would collide with a library item's.
        JellyfinItem(id = "genre:$name", name = name, type = "MusicGenre")
    }
}
