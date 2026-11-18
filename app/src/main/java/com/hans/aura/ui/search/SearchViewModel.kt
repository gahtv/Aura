package com.hans.aura.ui.search

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hans.aura.AuraApp
import com.hans.aura.data.CatalogHit
import com.hans.aura.data.JellyfinItem
import com.hans.aura.data.LibraryFilter
import com.hans.aura.data.MusicSource
import com.hans.aura.data.MusicSources
import com.hans.aura.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.hans.aura.data.SourceQueries

private const val TAG = "AURA_SEARCH"
private const val DEBOUNCE_MS = 220L

/** How often an import job is polled, and for how long before it is called stuck. */
private const val JOB_POLL_MS = 2_000L
private const val JOB_MAX_POLLS = 450 // ~15 minutes

data class SearchUiState(
    val query: String = "",
    val tracks: List<JellyfinItem>? = null,
    val albums: List<JellyfinItem>? = null,
    val artists: List<JellyfinItem>? = null,
    val searching: Boolean = false,
    val recentSearches: List<String> = emptyList(),
    val error: String? = null,
    /** Bumped on every submit so ENTER re-runs a search whose query did not change. */
    val submitTick: Int = 0,
    val catalog: CatalogUiState = CatalogUiState()
) {
    val allEmpty: Boolean
        get() = tracks.isNullOrEmpty() && albums.isNullOrEmpty() && artists.isNullOrEmpty()

    /** Nothing found anywhere — neither the library nor the catalog had a match. */
    val nothingAnywhere: Boolean
        get() = allEmpty && catalog.hits.isEmpty()
}

/**
 * "From the catalog" state: hits for the current query plus the import jobs the
 * user started from them, keyed by catalog album id.
 *
 * Only populated when [MusicSource.supportsCatalogImport] is true, so the
 * server-backed build never sees a hit and renders the screen it always has.
 */
data class CatalogUiState(
    val hits: List<CatalogHit> = emptyList(),
    val loading: Boolean = false,
    val imports: Map<String, CatalogImport> = emptyMap(),
    /** Incremented when an import finishes and the library should be re-read. */
    val libraryChangedTick: Int = 0
)

/** One in-flight (or finished) import, as the row shows it. */
data class CatalogImport(
    val label: String,
    val running: Boolean = true,
    val failed: Boolean = false
)

/**
 * Search execution and recent-search history.
 *
 * ONE getItems query (`Audio,MusicAlbum,MusicArtist,Playlist`) split client-side
 * by type — the three shelves are rendered stacked on one page, never as tabs.
 * Typing debounces; ENTER/resubmit runs immediately.
 *
 * When the installed source can import (`supportsCatalogImport`), the same term
 * also runs against the catalog and the import-job polling lives here, so the
 * screen stays a pure renderer of [SearchUiState].
 */
class SearchViewModel(private val app: AuraApp) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState(recentSearches = Prefs.recentSearches()))
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    /** One poller per catalog album, so repeated Imports cannot fork a second chain. */
    private val importJobs = mutableMapOf<String, Job>()

    private val userId: String get() = app.jellyfin.auth.session?.userId ?: ""

    private val source: MusicSource? get() = MusicSources.current

    private val canImport: Boolean get() = source?.supportsCatalogImport == true

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

    /**
     * Starts the import for [hit] and polls its job every [JOB_POLL_MS].
     *
     * A second tap on a row that is already importing (or already imported) is a
     * no-op; a failed import can be retried.
     */
    fun importCatalogAlbum(hit: CatalogHit) {
        val src = source ?: return
        if (src.supportsCatalogImport.not()) return
        val existing = _state.value.catalog.imports[hit.id]
        if (existing != null && !existing.failed) return
        if (importJobs[hit.id]?.isActive == true) return

        setImport(hit.id, CatalogImport(label = "Queued"))
        Log.d(TAG, "import id=${hit.id} title=${hit.title}")
        importJobs[hit.id] = viewModelScope.launch {
            val jobId = withContext(Dispatchers.IO) {
                runCatching { src.importFromCatalog(hit.id) }.getOrNull()
            }
            if (jobId.isNullOrBlank()) {
                Log.w(TAG, "import id=${hit.id} did not start")
                setImport(hit.id, CatalogImport("Couldn't start import", running = false, failed = true))
                return@launch
            }
            pollImport(hit.id, jobId)
        }
    }

    private suspend fun pollImport(albumId: String, jobId: String) {
        repeat(JOB_MAX_POLLS) {
            delay(JOB_POLL_MS)
            val jobState = withContext(Dispatchers.IO) {
                runCatching { source?.catalogImportState(jobId) }.getOrNull()
            } ?: return@repeat // transient (offline, 5xx): keep trying

            when (val label = jobLabel(jobState)) {
                null -> Unit
                LABEL_READY -> {
                    // Drop the source's caches first, then let the screen ask the
                    // navigation host for a library refresh: the album has to be
                    // in /v1/library by the time Albums re-reads it.
                    withContext(Dispatchers.IO) {
                        runCatching { source?.refreshAll() }
                    }
                    _state.update { st ->
                        st.copy(
                            catalog = st.catalog.copy(
                                imports = st.catalog.imports + (albumId to CatalogImport(
                                    label = "Ready \u2014 refreshing library",
                                    running = false
                                )),
                                libraryChangedTick = st.catalog.libraryChangedTick + 1
                            )
                        )
                    }
                    Log.d(TAG, "import id=$albumId ready")
                    return
                }
                else -> {
                    val failed = jobState in TERMINAL_FAILURES
                    setImport(albumId, CatalogImport(label, running = !failed, failed = failed))
                    if (failed) return
                }
            }
        }
        setImport(albumId, CatalogImport("Still working \u2014 check Albums", running = false))
    }

    private fun setImport(albumId: String, value: CatalogImport) {
        _state.update { st ->
            st.copy(catalog = st.catalog.copy(imports = st.catalog.imports + (albumId to value)))
        }
    }

    private fun scheduleSearch(immediate: Boolean) {
        searchJob?.cancel()
        val query = _state.value.query.trim()
        if (query.length < 2) {
            _state.update {
                it.copy(
                    tracks = emptyList(), albums = emptyList(), artists = emptyList(),
                    searching = false, error = null,
                    catalog = it.catalog.copy(hits = emptyList(), loading = false)
                )
            }
            return
        }
        searchJob = viewModelScope.launch {
            if (!immediate) delay(DEBOUNCE_MS)
            runSearch(query)
            // The catalog half runs after the library half so local results paint
            // first; a slow index can never hold the shelves back.
            searchCatalog(query)
        }
    }

    private suspend fun runSearch(term: String) {
        _state.update { it.copy(searching = true) }
        runCatching {
            withContext(Dispatchers.IO) {
                // Search is a browse query: hidden libraries are not queried.
                SourceQueries.items(LibraryFilter.ItemsQuery(
                        searchTerm = term, recursive = true,
                        includeItemTypes = "Audio,MusicAlbum,MusicArtist,Playlist",
                        limit = 50
                    )
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

    /** Catalog hits for [term]; a no-op (and no network) for sources without one. */
    private suspend fun searchCatalog(term: String) {
        val src = source ?: return
        if (!src.supportsCatalogImport) return
        _state.update { it.copy(catalog = it.catalog.copy(loading = true)) }
        val hits = withContext(Dispatchers.IO) {
            runCatching { src.catalogSearch(term) }.getOrDefault(emptyList())
        }
        Log.d(TAG, "catalog term=\"$term\" hits=${hits.size}")
        _state.update { it.copy(catalog = it.catalog.copy(hits = hits, loading = false)) }
    }

    override fun onCleared() {
        importJobs.values.forEach { it.cancel() }
        importJobs.clear()
        super.onCleared()
    }

    private companion object {
        const val LABEL_READY = "ready"

        /** Job states that mean "nothing more will happen without the user". */
        val TERMINAL_FAILURES = setOf("failed", "cancelled")

        /** Index job states -> the one line shown on the row. */
        fun jobLabel(state: String): String? = when (state.lowercase()) {
            "queued", "pending" -> "Queued"
            "resolving" -> "Resolving\u2026"
            "downloading" -> "Downloading\u2026"
            "decrypting" -> "Decrypting\u2026"
            "packaging" -> "Packaging\u2026"
            "tagging" -> "Tagging\u2026"
            "ingesting" -> "Adding to library\u2026"
            "ready", "ready_partial" -> LABEL_READY
            "failed" -> "Import failed"
            "cancelled" -> "Import cancelled"
            else -> null
        }
    }
}
