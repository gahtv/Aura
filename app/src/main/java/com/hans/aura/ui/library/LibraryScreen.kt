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
    onOpenArtist: (JellyfinItem) -> Unit
) {
    val session = app.jellyfin.auth.session!!
    var sort by remember { mutableStateOf(LibrarySortPrefs.get(kind)) }
    var desc by remember { mutableStateOf(sort.defaultDesc) }
    // Sort-specific keys: switching sort reloads fresh and BACK restores the
    // exact list + scroll of the sort that was active.
    val cacheKey = "lib_${kind.lowercase()}:${session.userId}:${sort.sortBy}:$desc"
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
            val result = when (kind) {
                "Artists" -> app.jellyfin.api.getAlbumArtists(
                    userId = session.userId, sortBy = sort.sortBy, sortOrder = order,
                    limit = PAGE_SIZE, startIndex = items.size
                )
                "Albums" -> app.jellyfin.api.getItems(
                    userId = session.userId, includeItemTypes = "MusicAlbum",
                    recursive = true, sortBy = sort.sortBy, sortOrder = order,
                    limit = PAGE_SIZE, startIndex = items.size
                )
                "Tracks" -> app.jellyfin.api.getItems(
                    userId = session.userId, includeItemTypes = "Audio",
                    recursive = true, sortBy = sort.sortBy, sortOrder = order,
                    limit = PAGE_SIZE, startIndex = items.size
                )
                "Playlists" -> app.jellyfin.api.getItems(
                    userId = session.userId, includeItemTypes = "Playlist",
                    recursive = true, sortBy = sort.sortBy, sortOrder = order,
                    limit = PAGE_SIZE, startIndex = items.size
                )
                else -> error("Unknown kind $kind")
            }
            total = result.totalRecordCount
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

    val contentFocus = remember { androidx.compose.ui.focus.FocusRequester() }
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
private fun SortPill(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
    val bg = when {
        selected && focused -> androidx.compose.ui.graphics.Color(0x66E8A44C)
        selected -> androidx.compose.ui.graphics.Color(0xFFE8A44C)
        focused -> androidx.compose.ui.graphics.Color(0xFF3A322A)
        else -> androidx.compose.ui.graphics.Color.Transparent
    }
    Box(
        Modifier
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
