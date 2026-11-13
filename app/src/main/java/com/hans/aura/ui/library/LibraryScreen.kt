@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.hans.aura.ui.library

import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
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
    val cacheKey = "lib_${kind.lowercase()}:${session.userId}"
    var items by remember { mutableStateOf<List<JellyfinItem>>(emptyList()) }
    var initialized by remember { mutableStateOf(false) }
    var total by remember { mutableStateOf(Int.MAX_VALUE) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val current by player.current.collectAsState()

    // Album Atmos badges (Albums tab only): whole-library scan, cached per session.
    var atmosAlbumIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(cacheKey, refreshKey) {
        if (kind != "Albums") return@LaunchedEffect
        atmosAlbumIds = com.hans.aura.data.AtmosCatalog.albumIds(app.jellyfin.api, session.userId)
    }

    suspend fun loadMore() {
        if (loading || items.size >= total) return
        loading = true
        runCatching {
            val result = when (kind) {
                "Artists" -> app.jellyfin.api.getAlbumArtists(
                    userId = session.userId, limit = PAGE_SIZE, startIndex = items.size
                )
                "Albums" -> app.jellyfin.api.getItems(
                    userId = session.userId, includeItemTypes = "MusicAlbum",
                    recursive = true, sortBy = "SortName",
                    limit = PAGE_SIZE, startIndex = items.size
                )
                "Tracks" -> app.jellyfin.api.getItems(
                    userId = session.userId, includeItemTypes = "Audio",
                    recursive = true, sortBy = "Album,ParentIndexNumber,IndexNumber,SortName",
                    limit = PAGE_SIZE, startIndex = items.size
                )
                "Playlists" -> app.jellyfin.api.getItems(
                    userId = session.userId, includeItemTypes = "Playlist",
                    recursive = true, sortBy = "SortName",
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

    Box(Modifier.fillMaxSize()) {
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
                        onClick = { player.playQueue(items, i) },
                        focusKeeper = focusKeeper,
                        onFocused = { focusedId = it }
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
                        onFocused = { focusedId = it }
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
