@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.hans.aura.ui.library

import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hans.aura.AuraApp
import com.hans.aura.data.JellyfinItem
import com.hans.aura.data.LibraryCache
import com.hans.aura.ui.common.ArtworkCard
import com.hans.aura.ui.common.TrackRow
import com.hans.aura.ui.player.PlayerConnection
import com.hans.aura.ui.theme.TextPrimary
import com.hans.aura.ui.theme.TextSecondary

/**
 * Whole-library views: Artists, Albums, Tracks, Playlists.
 * Pages of 60 items load as the user approaches the end of the list.
 */
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
private val AZ_LETTERS: List<String> = listOf("#") + ('A'..'Z').map { it.toString() }
private const val AZ_FIRST = "#"

private fun sortModesFor(kind: String): List<SortMode> = when (kind) {
    "Artists" -> listOf(SortMode.TITLE, SortMode.DATE_ADDED, SortMode.PLAY_COUNT, SortMode.RANDOM)
    "Albums" -> listOf(SortMode.TITLE, SortMode.ALBUM_ARTIST, SortMode.DATE_ADDED, SortMode.LENGTH, SortMode.PLAY_COUNT, SortMode.RANDOM)
    "Tracks" -> listOf(SortMode.TITLE, SortMode.ALBUM, SortMode.ALBUM_ARTIST, SortMode.DATE_ADDED, SortMode.LENGTH, SortMode.PLAY_COUNT, SortMode.RANDOM)
    else -> listOf(SortMode.TITLE, SortMode.DATE_ADDED, SortMode.RANDOM) // Playlists
}

/** Session-scoped: the chosen sort survives navigating away and back. */
private object LibrarySortPrefs {
    private val byKind = mutableMapOf<String, SortMode>()
    fun get(kind: String): SortMode =
        byKind[kind] ?: sortModesFor(kind).first()
    fun set(kind: String, mode: SortMode) { byKind[kind] = mode }
}

@Composable
fun LibraryScreen(
    app: AuraApp,
    kind: String, // "Artists" | "Albums" | "Tracks" | "Playlists"
    player: PlayerConnection,
    refreshKey: Int = 0,
    focusTick: Int = 0,
    onOpenCollection: (JellyfinItem) -> Unit,
    onOpenArtist: (JellyfinItem) -> Unit,
    // Genres live behind a pill INSIDE the Albums tab: a sixth top-bar tab does
    // not fit 960dp next to five tabs, the pill and two icon buttons.
    onOpenGenres: (() -> Unit)? = null
) {
    val session = app.jellyfin.auth.session!!
    var sort by remember { mutableStateOf(LibrarySortPrefs.get(kind)) }
    var desc by remember { mutableStateOf(sort.defaultDesc) }
    // A-Z jump: null = no letter filter. When set, the paged query starts at
    // that letter (nameStartsWithOrGreater) instead of index 0 of the alphabet.
    var letter by androidx.compose.runtime.saveable.rememberSaveable(kind) {
        mutableStateOf<String?>(null)
    }
    var azOpen by remember { mutableStateOf(false) }
    // Set when a letter is picked so the NEW list starts at the top; BACK
    // restores the same key and must NOT be scrolled to 0.
    var justJumped by remember { mutableStateOf(false) }
    // Sort-specific keys: switching sort reloads fresh and BACK restores the
    // exact list + scroll of the sort that was active.
    val cacheKey = "lib_${kind.lowercase()}:${session.userId}:${sort.sortBy}:$desc" +
        (letter?.let { ":az_$it" } ?: "")
    var items by remember { mutableStateOf<List<JellyfinItem>>(emptyList()) }
    var initialized by remember { mutableStateOf(false) }
    var total by remember { mutableStateOf(Int.MAX_VALUE) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val current by player.current.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    fun trackMenu(track: JellyfinItem, onRemove: (() -> Unit)? = null) =
        com.hans.aura.ui.common.ContextMenuHost.open(
            com.hans.aura.ui.common.ContextMenus.forTrack(
                app, player, scope, track,
                onOpenArtist = onOpenArtist, onOpenCollection = onOpenCollection,
                onRemoveFromPlaylist = onRemove
            )
        )

    fun albumMenu(item: JellyfinItem) =
        com.hans.aura.ui.common.ContextMenuHost.open(
            com.hans.aura.ui.common.ContextMenus.forAlbum(
                app, player, scope, item, onOpenArtist = onOpenArtist
            )
        )

    // Atmos badges (Albums grid + Tracks rows): whole-library scan, cached per session.
    var atmosAlbumIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(cacheKey, refreshKey) {
        if (kind != "Albums" && kind != "Tracks") return@LaunchedEffect
        atmosAlbumIds = com.hans.aura.data.AtmosCatalog.albumIds(app.jellyfin.api, session.userId)
    }

    suspend fun loadMore() {
        if (loading || items.size >= total) return
        loading = true
        runCatching {
            val order = if (desc) "Descending" else "Ascending"
            val fromLetter = letter
            val result = if (fromLetter != null) {
                // A-Z jump: the paged query STARTS at the letter, so page 1 is
                // the first item at/after it and we never walk the alphabet.
                pageFromLetter(
                    api = app.jellyfin.api,
                    userId = session.userId,
                    kind = kind,
                    letter = fromLetter,
                    limit = PAGE_SIZE,
                    startIndex = items.size
                )
            } else when (kind) {
                "Artists" -> com.hans.aura.data.LibraryFilter.queryAlbumArtists(
                    app.jellyfin.api, userId = session.userId, sortBy = sort.sortBy, sortOrder = order,
                    limit = PAGE_SIZE, startIndex = items.size
                )
                "Albums" -> com.hans.aura.data.LibraryFilter.queryItems(
                    app.jellyfin.api, session.userId,
                    com.hans.aura.data.LibraryFilter.ItemsQuery(
                        includeItemTypes = "MusicAlbum",
                        recursive = true, sortBy = sort.sortBy, sortOrder = order,
                        limit = PAGE_SIZE, startIndex = items.size
                    )
                )
                "Tracks" -> com.hans.aura.data.LibraryFilter.queryItems(
                    app.jellyfin.api, session.userId,
                    com.hans.aura.data.LibraryFilter.ItemsQuery(
                        includeItemTypes = "Audio",
                        recursive = true, sortBy = sort.sortBy, sortOrder = order,
                        limit = PAGE_SIZE, startIndex = items.size
                    )
                )
                "Playlists" -> app.jellyfin.api.getItems(
                    userId = session.userId, includeItemTypes = "Playlist",
                    recursive = true, sortBy = sort.sortBy, sortOrder = order,
                    limit = PAGE_SIZE, startIndex = items.size
                )
                else -> error("Unknown kind $kind")
            }
            // A-Z jump skips the count: pagination ends on a short page instead
            // of trusting a total that belongs to the whole (unfiltered) list.
            total = if (fromLetter != null) {
                if (result.items.size < PAGE_SIZE) items.size + result.items.size else Int.MAX_VALUE
            } else result.totalRecordCount
            items = items + result.items
            com.hans.aura.data.PagedLibrary.put(cacheKey, items, total)
        }.onFailure { error = it.message }
        loading = false
    }

    LaunchedEffect(cacheKey, refreshKey) {
        items = emptyList()
        total = Int.MAX_VALUE
        initialized = false
        error = null
        // Session-scoped paged store first: keeps deep-scroll state alive
        // across BACK so focus restoration can find the exact item.
        com.hans.aura.data.PagedLibrary.get(cacheKey)?.let { page ->
            items = page.items
            total = page.total
            initialized = true
        }
        if (!initialized) {
            LibraryCache.get(cacheKey)?.let { cached ->
                if (cached.isNotEmpty()) {
                    items = cached
                    initialized = true
                }
            }
        }
        if (!initialized) {
            loadMore()
            initialized = true
        }
        if (items.isNotEmpty()) {
            com.hans.aura.data.PagedLibrary.put(cacheKey, items, total)
            LibraryCache.put(cacheKey, items.take(PAGE_SIZE))
        }
    }

    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()
    val nearGridEnd by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 10
        }
    }
    val nearListEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 10
        }
    }
    LaunchedEffect(nearGridEnd, items.size) {
        if (kind != "Tracks" && nearGridEnd && initialized && items.isNotEmpty() && items.size < total) {
            loadMore()
        }
    }
    LaunchedEffect(nearListEnd, items.size) {
        if (kind == "Tracks" && nearListEnd && initialized && items.isNotEmpty() && items.size < total) {
            loadMore()
        }
    }
    // A-Z jump lands on the TOP of the new list; a grid still holding the
    // previous list's deep offset would drop the user in the middle of the
    // "B"s. BACK restores the same key and is deliberately left alone.
    LaunchedEffect(cacheKey) {
        if (!justJumped) return@LaunchedEffect
        justJumped = false
        kotlinx.coroutines.delay(50)
        runCatching {
            if (kind == "Tracks") listState.scrollToItem(0) else gridState.scrollToItem(0)
        }
    }

    val contentFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val azPillFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val railFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val focusKeeper = remember { com.hans.aura.ui.common.FocusKeeper() }
    var focusedId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    com.hans.aura.ui.common.RestoreFocusOnBack(focusTick, contentFocus, focusedId, focusKeeper)

    Column(Modifier.fillMaxSize()) {
        // Sort pills: UP from the grid reaches them; the ACTIVE pill toggles
        // its direction on click (Random has no direction).
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 32.dp, end = 32.dp, top = 6.dp, bottom = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Genres pill (Albums only): opens the genre grid, which owns its
            // own screen in the back-stack so BACK returns here.
            if (kind == "Albums" && onOpenGenres != null) {
                SortPill(label = "Genres", selected = false, onClick = { onOpenGenres() })
            }
            sortModesFor(kind).forEach { mode ->
                val active = mode == sort
                SortPill(
                    label = if (active && mode != SortMode.RANDOM) {
                        mode.label + if (desc) " \u25BC" else " \u25B2"
                    } else mode.label,
                    selected = active,
                    onClick = {
                        if (active && mode != SortMode.RANDOM) desc = !desc
                        else if (!active) {
                            sort = mode
                            desc = mode.defaultDesc
                            LibrarySortPrefs.set(kind, mode)
                        }
                    }
                )
            }
            // A-Z jump pill: only where the grid is alphabetical (Albums/Artists).
            if (kind == "Albums" || kind == "Artists") {
                SortPill(
                    label = letter?.let { "A\u2013Z \u00B7 $it" } ?: "A\u2013Z",
                    selected = azOpen || letter != null,
                    modifier = Modifier.focusRequester(azPillFocus),
                    onClick = { azOpen = !azOpen }
                )
            }
        }
        // Letter rail: an inline strip above the grid (no overlay, so the grid
        // keeps its scroll and focus restore). UP from the grid reaches the
        // pills, UP again the rail; BACK closes the rail without leaving the tab.
        if (azOpen) {
            androidx.activity.compose.BackHandler(enabled = azOpen) {
                azOpen = false
                scope.launch { kotlinx.coroutines.delay(60); azPillFocus.requestFocus() }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 32.dp, end = 32.dp, bottom = 6.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AZ_LETTERS.forEach { candidate ->
                    LetterChip(
                        label = candidate,
                        selected = candidate == letter,
                        modifier = if (candidate == AZ_FIRST) Modifier.focusRequester(railFocus) else Modifier,
                        onClick = {
                            // A-Z only means anything on SortName, so the jump
                            // pins the sort; the key changes and page 1 reloads.
                            azOpen = false
                            letter = candidate
                            justJumped = true
                            sort = SortMode.TITLE
                            desc = false
                            LibrarySortPrefs.set(kind, SortMode.TITLE)
                            android.util.Log.d("AURA_AZ", "letter=$candidate kind=$kind")
                            // The rail disappears when a letter is taken; hand
                            // focus to the grid or the selector is stranded.
                            scope.launch {
                                kotlinx.coroutines.delay(150)
                                runCatching { contentFocus.requestFocus() }
                            }
                        }
                    )
                }
            }
            LaunchedEffect(azOpen) {
                if (azOpen) {
                    kotlinx.coroutines.delay(80)
                    runCatching { railFocus.requestFocus() }
                }
            }
        }
    Box(Modifier.fillMaxSize().weight(1f)) {
        when {
            error != null && items.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                androidx.tv.material3.Text("Library error: $error", color = TextPrimary)
            }
            !initialized && items.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                androidx.tv.material3.Text("Loading\u2026", color = TextSecondary, fontSize = 16.sp)
            }
            items.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                androidx.tv.material3.Text("Nothing here", color = TextSecondary, fontSize = 16.sp)
            }
            kind == "Tracks" -> LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp)
                    .focusRequester(contentFocus)
                    .focusRestorer()
            ) {
                itemsIndexed(items) { i, track ->
                    TrackRow(
                        index = i + 1,
                        track = track,
                        isCurrent = track.id == current?.id,
                        atmos = track.albumId != null && track.albumId in atmosAlbumIds,
                        onClick = { player.playQueue(items, i) },
                        focusKeeper = focusKeeper,
                        onFocused = { focusedId = it },
                        onLongPress = { trackMenu(track) }
                    )
                }
                item {
                    Spacer(Modifier.height(60.dp))
                    androidx.tv.material3.Text(
                        if (items.size < total) "Loading more\u2026" else "${items.size} tracks",
                        color = TextSecondary, fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                    Spacer(Modifier.height(120.dp))
                }
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                state = gridState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp)
                    .focusRequester(contentFocus)
                    .focusRestorer(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                items(items, key = { "${kind}_${it.id}" }) { item ->
                    ArtworkCard(
                        item = item,
                        serverUrl = session.serverUrl,
                        size = 210.dp,
                        badge = if (kind == "Albums" && item.id in atmosAlbumIds) "ATMOS" else null,
                        focusKeeper = focusKeeper,
                        onFocused = { focusedId = it },
                        onLongPress = if (kind != "Artists") {
                            { albumMenu(item) }
                        } else null
                    ) {
                        when (kind) {
                            "Artists" -> onOpenArtist(item)
                            else -> onOpenCollection(item)
                        }
                    }
                }
                item(span = { GridItemSpan(5) }) {
                    Column {
                        Spacer(Modifier.height(24.dp))
                        androidx.tv.material3.Text(
                            if (items.size < total) "Loading more\u2026"
                            else "${items.size} ${kind.lowercase()}",
                            color = TextSecondary, fontSize = 12.sp
                        )
                        Spacer(Modifier.height(120.dp))
                    }
                }
            }
        }
        }
    }
}

/** Sort pill: active = filled amber, focused = thick outline. Clicking the
 *  active pill (see call site) flips its direction. */
@Composable
private fun SortPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
    val bg = when {
        selected && focused -> androidx.compose.ui.graphics.Color(0x66E8A44C)
        selected -> androidx.compose.ui.graphics.Color(0xFFE8A44C)
        focused -> androidx.compose.ui.graphics.Color(0xFF3A322A)
        else -> androidx.compose.ui.graphics.Color.Transparent
    }
    Box(
        modifier
            .clip(shape)
            .background(bg)
            .border(
                width = if (focused) 3.dp else 0.dp,
                color = if (focused) androidx.compose.ui.graphics.Color(0xFFE8A44C)
                else androidx.compose.ui.graphics.Color.Transparent,
                shape = shape
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        androidx.tv.material3.Text(
            label,
            fontSize = 13.sp,
            color = if (selected) androidx.compose.ui.graphics.Color(0xFF191410) else TextPrimary
        )
    }
}

/**
 * One letter chip on the A-Z rail. Selected = filled amber (the jump in
 * effect), focused = bright 3dp ring, so the D-pad cursor is never ambiguous.
 * onFocusChanged comes BEFORE clickable or the flag never flips.
 */
@Composable
private fun LetterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
    Box(
        modifier
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() }
            .clip(shape)
            .background(
                when {
                    selected && focused -> androidx.compose.ui.graphics.Color(0x99E8A44C)
                    selected -> androidx.compose.ui.graphics.Color(0xFFE8A44C)
                    focused -> androidx.compose.ui.graphics.Color(0xFF3A322A)
                    else -> androidx.compose.ui.graphics.Color(0xFF1F1A16)
                }
            )
            .border(
                width = if (focused) 3.dp else 0.dp,
                color = if (focused) androidx.compose.ui.graphics.Color(0xFFF2EDE6)
                else androidx.compose.ui.graphics.Color.Transparent,
                shape = shape
            )
            .padding(horizontal = 13.dp, vertical = 8.dp)
    ) {
        androidx.tv.material3.Text(
            label,
            fontSize = 14.sp,
            color = if (selected) androidx.compose.ui.graphics.Color(0xFF191410) else TextPrimary
        )
    }
}

/**
 * Page of the Albums/Artists grid that STARTS at [letter]
 * (Jellyfin's `nameStartsWithOrGreater`, compared against SortName).
 *
 * Browse queries must honour the hidden-library selection, and
 * data/LibraryFilter.kt (owned elsewhere) does not pass this parameter through,
 * so the jump re-uses LibraryFilter's public view of the selection and fans out
 * per visible music library itself. With nothing hidden it is a single query.
 */
private suspend fun pageFromLetter(
    api: com.hans.aura.data.JellyfinApi,
    userId: String,
    kind: String,
    letter: String,
    limit: Int,
    startIndex: Int
): com.hans.aura.data.ItemsResponse {
    suspend fun onePage(
        parentId: String?,
        start: Int,
        size: Int
    ): com.hans.aura.data.ItemsResponse =
        if (kind == "Artists") {
            api.getAlbumArtists(
                userId = userId,
                sortBy = "SortName",
                sortOrder = "Ascending",
                limit = size,
                startIndex = start,
                fields = "PrimaryImageAspectRatio",
                imageTypeLimit = 1,
                parentId = parentId,
                nameStartsWithOrGreater = letter
            )
        } else {
            api.getItems(
                userId = userId,
                includeItemTypes = "MusicAlbum",
                recursive = true,
                sortBy = "SortName",
                sortOrder = "Ascending",
                limit = size,
                startIndex = start,
                parentId = parentId,
                nameStartsWithOrGreater = letter
            )
        }

    if (!com.hans.aura.data.LibraryFilter.isActive()) return onePage(null, startIndex, limit)

    val libs = com.hans.aura.data.LibraryFilter.visibleLibraries().filter {
        it.collectionType == null || it.collectionType.equals("music", ignoreCase = true)
    }
    if (libs.isEmpty()) return com.hans.aura.data.ItemsResponse()
    val wanted = startIndex + limit
    val responses = libs.mapNotNull { lib ->
        runCatching { onePage(lib.id, 0, wanted) }.getOrNull()
    }
    val all = responses.flatMap { it.items }
        .distinctBy { it.id }
        .sortedBy { (it.sortName ?: it.name).lowercase() }
    return com.hans.aura.data.ItemsResponse(
        items = all.drop(startIndex).take(limit),
        totalRecordCount = responses.sumOf { it.totalRecordCount },
        startIndex = startIndex
    )
}
