package com.hans.aura.ui.library

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hans.aura.AuraApp
import com.hans.aura.data.AtmosFilter
import com.hans.aura.data.LibraryCache
import com.hans.aura.data.SourceQueries
import com.hans.aura.model.MediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AURA_LIB"

/** Page size for every browse tab (Artists / Albums / Tracks / Playlists). */
private const val PAGE_SIZE = 60

/** Server-side sort fields (Jellyfin SortBy). Random pages independently, so
 *  consecutive pages under Random may overlap or miss items — accepted. */
enum class SortMode(val label: String, val sortBy: String, val defaultDesc: Boolean) {
    TITLE("Title", "SortName", false),
    ALBUM("Album", "Album", false),
    ALBUM_ARTIST("Album artist", "AlbumArtist", false),
    DATE_ADDED("Date added", "DateCreated", true),
    LENGTH("Length", "Runtime", true),
    PLAY_COUNT("Most played", "PlayCount", true),
    RANDOM("Random", "Random", false)
}

/** A-Z rail: "#" catches numerics/symbols, then A-Z. */
val AZ_LETTERS: List<String> = listOf("#") + ('A'..'Z').map { it.toString() }

fun sortModesFor(kind: String): List<SortMode> = when (kind) {
    "Artists" -> listOf(SortMode.TITLE, SortMode.DATE_ADDED, SortMode.PLAY_COUNT, SortMode.RANDOM)
    "Albums" -> listOf(SortMode.TITLE, SortMode.ALBUM_ARTIST, SortMode.DATE_ADDED, SortMode.LENGTH, SortMode.PLAY_COUNT, SortMode.RANDOM)
    "Tracks" -> listOf(SortMode.TITLE, SortMode.ALBUM, SortMode.ALBUM_ARTIST, SortMode.DATE_ADDED, SortMode.LENGTH, SortMode.PLAY_COUNT, SortMode.RANDOM)
    else -> listOf(SortMode.TITLE, SortMode.DATE_ADDED, SortMode.RANDOM) // Playlists
}

/** Session-scoped: the chosen sort survives navigating away and back. */
private object LibrarySortPrefs {
    private val byKind = mutableMapOf<String, SortMode>()
    fun get(key: String, kind: String): SortMode = byKind[key] ?: sortModesFor(kind).first()
    fun set(kind: String, mode: SortMode) { byKind[kind] = mode }
}

/**
 * "Only Atmos" per-view filter (the icon pill). Session-scoped and separate from
 * the Settings preference: the pill is for "just show me the Atmos ones here",
 * the preference is for "never show me anything else anywhere".
 */
private object LibraryAtmosPrefs {
    private val byKind = mutableMapOf<String, Boolean>()
    fun get(kind: String): Boolean = byKind[kind] ?: false
    fun set(kind: String, on: Boolean) { byKind[kind] = on }
}

/**
 * State of one browse tab: paging, the four filter/sort axes (sort, direction,
 * Liked, per-view Atmos, A-Z letter) and the Atmos badge catalog.
 */
@Immutable
data class LibraryUiState(
    val kind: String = "Albums",
    val items: List<MediaItem> = emptyList(),
    val total: Int = Int.MAX_VALUE,
    val initialized: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val sort: SortMode = SortMode.TITLE,
    val desc: Boolean = false,
    val likedOnly: Boolean = false,
    val atmosView: Boolean = false,
    val atmosEffective: Boolean = false,
    val letter: String? = null,
    val atmosAlbumIds: Set<String> = emptySet(),
    val cacheKey: String = "",
    /** Bumped when an A-Z jump lands on the TOP of a new list (not on BACK). */
    val jumpTick: Int = 0
) {
    val atmosCapable: Boolean get() = kind == "Albums" || kind == "Tracks"
    val azCapable: Boolean get() = kind == "Albums" || kind == "Artists"
    val allEmpty: Boolean get() = items.isEmpty()
}

class LibraryViewModel(
    private val app: AuraApp,
    private val kind: String,
    likedOnly: Boolean = false
) : ViewModel() {

    private val preferenceKey = if (likedOnly) "Liked" else kind
    private val capable = kind == "Albums" || kind == "Tracks"
    private val initialSort = LibrarySortPrefs.get(preferenceKey, kind)

    private val _state = MutableStateFlow(
        LibraryUiState(
            kind = kind,
            sort = initialSort,
            desc = initialSort.defaultDesc,
            likedOnly = likedOnly,
            atmosView = capable && LibraryAtmosPrefs.get(preferenceKey)
        )
    )
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var loadGeneration = 0L
    // AppRoot's refreshKey starts at 0; the first LaunchedEffect(0) must not
    // double-load what init already fetched.
    private var refreshKeySeen = 0

    private val userId: String get() = app.jellyfin.auth.session?.userId ?: ""
    private val playlistUserId = userId.takeIf { kind == "Playlists" }
    private var playlistRevisionSeen = 0L

    init {
        reload()
        if (playlistUserId != null) {
            viewModelScope.launch {
                app.container.media.playlistRevisions.collect {
                    if (playlistUserId == userId && playlistRevisionSeen != currentPlaylistRevision()) reload()
                }
            }
        }
    }

    private fun currentPlaylistRevision(): Long =
        playlistUserId?.let { app.container.media.playlistRevisions.value[it] } ?: 0L

    /** Settings > Refresh library (and a library-selection change) bumps this. */
    fun onRefreshKey(key: Int) {
        if (key == refreshKeySeen) return
        refreshKeySeen = key
        reload()
    }

    fun retry() = reload()

    fun refreshLikedIfEvicted() {
        val current = _state.value
        if (!current.likedOnly || !current.initialized || current.loading ||
            current.error != null || app.container.media.hasCachedPage(current.cacheKey)
        ) return

        val generation = ++loadGeneration
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            loadPage(current.cacheKey, generation, replaceLoadedRange = true)
        }
    }

    fun loadNextPage() {
        val snapshot = _state.value
        if (snapshot.loading || snapshot.items.size >= snapshot.total) return
        val generation = loadGeneration
        viewModelScope.launch { loadPage(snapshot.cacheKey, generation) }
    }

    /** Clicking the active pill flips its direction (Random has none). */
    fun onSortClick(mode: SortMode) {
        val current = _state.value
        val wasLetter = current.letter
        if (wasLetter != null) logAz("cleared reason=${mode.label} kind=$kind was=$wasLetter")
        if (mode == current.sort && mode != SortMode.RANDOM) {
            _state.update { it.copy(letter = null, desc = !it.desc) }
        } else if (mode != current.sort) {
            LibrarySortPrefs.set(preferenceKey, mode)
            _state.update { it.copy(letter = null, sort = mode, desc = mode.defaultDesc) }
        } else {
            _state.update { it.copy(letter = null) }
        }
        reload()
    }

    fun toggleAtmos() {
        val current = _state.value
        current.letter?.let { logAz("cleared reason=atmos kind=$kind was=$it") }
        val next = !current.atmosView
        LibraryAtmosPrefs.set(preferenceKey, next)
        Log.d("AURA_ATMOS", "view=${if (next) "on" else "off"} kind=$kind")
        _state.update { it.copy(letter = null, atmosView = next) }
        reload()
    }

    /**
     * A-Z jump. The paged query for a letter pins SortName+Ascending, so the
     * sort is pinned too; jumpTick asks the screen to scroll to the top.
     */
    fun selectLetter(letter: String) {
        Log.d("AURA_AZ", "letter=$letter kind=$kind")
        LibrarySortPrefs.set(kind, SortMode.TITLE)
        _state.update {
            it.copy(letter = letter, sort = SortMode.TITLE, desc = false, jumpTick = it.jumpTick + 1)
        }
        reload()
    }

    /** "All" clears the letter without leaving the tab. */
    fun clearLetter(letter: String?) {
        if (letter == null) {
            Log.d("AURA_AZ", "cleared reason=all kind=$kind was=${_state.value.letter}")
        }
        _state.update {
            it.copy(letter = null, jumpTick = if (letter == null) it.jumpTick + 1 else it.jumpTick)
        }
        reload()
    }

    // ---- loading -----------------------------------------------------------

    private fun reload() {
        val generation = ++loadGeneration
        playlistRevisionSeen = currentPlaylistRevision()
        loadJob?.cancel()
        val current = _state.value
        val effective = current.atmosCapable && (current.atmosView || AtmosFilter.on)
        val key = cacheKeyFor(current, effective)
        _state.update {
            it.copy(
                items = emptyList(),
                total = Int.MAX_VALUE,
                initialized = false,
                loading = false,
                error = null,
                atmosEffective = effective,
                cacheKey = key
            )
        }
        scanAtmos(generation)
        loadJob = viewModelScope.launch {
            if (!isCurrentLoad(generation)) return@launch
            val cached = app.container.media.cachedLibraryPage(key)
            if (cached != null) {
                _state.update { it.copy(items = cached.items, total = cached.total, initialized = true) }
                return@launch
            }
            _state.update { it.copy(initialized = true) }
            loadPage(key, generation)
        }
    }

    private fun isCurrentLoad(generation: Long): Boolean =
        generation == loadGeneration && playlistRevisionSeen == currentPlaylistRevision()

    private suspend fun loadPage(key: String, generation: Long, replaceLoadedRange: Boolean = false) {
        if (!isCurrentLoad(generation)) return
        val snapshot = _state.value
        val refreshCachedPage = app.container.media.cachedPageRequiresRefresh(key)
        val accumulatedPageMissing = !replaceLoadedRange && snapshot.items.isNotEmpty() &&
            !app.container.media.hasCachedPage(key)
        val replace = replaceLoadedRange || refreshCachedPage || accumulatedPageMissing
        if (snapshot.loading || (!replace && snapshot.items.size >= snapshot.total)) return
        _state.update { it.copy(loading = true) }
        val startIndex = if (replace) 0 else snapshot.items.size
        val limit = when {
            refreshCachedPage || accumulatedPageMissing -> snapshot.items.size + PAGE_SIZE
            replaceLoadedRange -> maxOf(PAGE_SIZE, snapshot.items.size)
            else -> PAGE_SIZE
        }
        runCatching {
            app.container.media.libraryPage(
                key = key,
                kind = kind,
                sortBy = snapshot.sort.sortBy,
                desc = snapshot.desc,
                likedOnly = snapshot.likedOnly,
                atmos = snapshot.atmosEffective,
                letter = snapshot.letter,
                startIndex = startIndex,
                limit = limit,
                replace = replace,
                valid = { isCurrentLoad(generation) }
            )
        }.onSuccess { result ->
            if (!isCurrentLoad(generation)) return@onSuccess
            _state.update { it.copy(items = result.items, total = result.total, loading = false, error = null) }
        }.onFailure { e ->
            if (e is CancellationException) throw e
            if (!isCurrentLoad(generation)) return@onFailure
            Log.w(TAG, "page kind=$kind key=$key failed: ${e.message}")
            _state.update {
                it.copy(loading = false, error = e.message ?: "Couldn't load from server")
            }
        }
    }

    /** Whole-library Atmos scan for the badges; cached per session by the catalog. */
    private fun scanAtmos(generation: Long) {
        if (!capable) return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { SourceQueries.atmosAlbumIds() }
            }.onSuccess { ids ->
                if (generation != loadGeneration) return@onSuccess
                _state.update { it.copy(atmosAlbumIds = ids) }
            }.onFailure {
                if (it is CancellationException) throw it
                if (generation != loadGeneration) return@onFailure
                Log.w(TAG, "atmos scan failed: ${it.message}")
            }
        }
    }

    private fun cacheKeyFor(state: LibraryUiState, atmosEffective: Boolean): String = when {
        atmosEffective ->
            "lib_atmos_${kind.lowercase()}:$userId:${state.sort.sortBy}:${state.desc}:${if (state.likedOnly) "fav" else "all"}"
        state.likedOnly -> "lib_liked:$userId:${state.sort.sortBy}:${state.desc}"
        state.letter != null ->
            "${LibraryCache.tabKeyPrefix(kind, userId)}${state.sort.sortBy}:${state.desc}:az_${state.letter}"
        else -> "${LibraryCache.tabKeyPrefix(kind, userId)}${state.sort.sortBy}:${state.desc}"
    }

    private fun logAz(message: String) = Log.d("AURA_AZ", "$message kind=$kind")
}
