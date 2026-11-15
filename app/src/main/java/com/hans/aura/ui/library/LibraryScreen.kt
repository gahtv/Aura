@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.hans.aura.ui.library

import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hans.aura.AuraApp
import com.hans.aura.data.JellyfinItem
import com.hans.aura.ui.common.ArtworkCard
import com.hans.aura.ui.common.ErrorPane
import com.hans.aura.ui.common.TrackRow
import com.hans.aura.ui.player.PlayerConnection
import com.hans.aura.ui.theme.TextPrimary
import com.hans.aura.ui.theme.TextSecondary

/**
 * Whole-library views: Artists, Albums, Tracks, Playlists.
 *
 * Layout and focus only — paging, the sort/filter axes and the cache keys live
 * in [LibraryViewModel], so navigating to a detail screen and pressing BACK
 * restores the exact loaded pages, sort and filters.
 */
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
    onOpenGenres: (() -> Unit)? = null,
    viewModel: LibraryViewModel = viewModel(
        // AppRoot's manual back-stack keeps LocalViewModelStoreOwner at the
        // Activity, so every tab needs its own key (and a new user needs a new
        // one or the previous session's pages would be served).
        key = "library:$kind:${app.jellyfin.auth.session?.userId ?: "anon"}",
        factory = viewModelFactory { initializer { LibraryViewModel(app, kind) } }
    )
) {
    val session = app.jellyfin.auth.session ?: return
    val state by viewModel.state.collectAsState()
    val items = state.items
    val current by player.current.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(refreshKey) { viewModel.onRefreshKey(refreshKey) }

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

    var azOpen by remember { mutableStateOf(false) }

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
        if (kind != "Tracks" && nearGridEnd && state.initialized && items.isNotEmpty() && items.size < state.total) {
            viewModel.loadNextPage()
        }
    }
    LaunchedEffect(nearListEnd, items.size) {
        if (kind == "Tracks" && nearListEnd && state.initialized && items.isNotEmpty() && items.size < state.total) {
            viewModel.loadNextPage()
        }
    }
    // A-Z jump lands on the TOP of the new list; a grid still holding the
    // previous list's deep offset would drop the user in the middle of the
    // "B"s. BACK restores the same key and is deliberately left alone.
    LaunchedEffect(state.jumpTick) {
        if (state.jumpTick == 0) return@LaunchedEffect
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
            // "Only Atmos" icon pill: Albums + Tracks. Hidden while the Settings
            // preference is on, because that already filters everything.
            if (state.atmosCapable && !com.hans.aura.data.AtmosFilter.on) {
                com.hans.aura.ui.common.AtmosFilterPill(
                    selected = state.atmosView,
                    onClick = { viewModel.toggleAtmos() }
                )
                Spacer(Modifier.width(12.dp))
            }
            // Liked pill lives INSIDE the Tracks sort row: the 960dp top bar
            // cannot fit a sixth tab, and this reads as a filter, not a place.
            if (kind == "Tracks") {
                SortPill(
                    label = "Liked",
                    leadingIcon = if (state.likedOnly) com.hans.aura.R.drawable.ic_favorite_fill1
                    else com.hans.aura.R.drawable.ic_favorite,
                    selected = state.likedOnly,
                    onClick = { viewModel.toggleLiked() }
                )
                Spacer(Modifier.width(18.dp))
            }
            // Genres pill (Albums only): opens the genre grid, which owns its
            // own screen in the back-stack so BACK returns here.
            if (kind == "Albums" && onOpenGenres != null) {
                SortPill(
                    label = "Genres",
                    leadingIcon = com.hans.aura.R.drawable.ic_category,
                    selected = false,
                    onClick = { onOpenGenres() }
                )
            }
            sortModesFor(kind).forEach { mode ->
                val active = mode == state.sort
                SortPill(
                    label = mode.label,
                    trailingIcon = if (active && mode != SortMode.RANDOM) {
                        if (state.desc) com.hans.aura.R.drawable.ic_arrow_drop_down
                        else com.hans.aura.R.drawable.ic_arrow_drop_up
                    } else null,
                    selected = active,
                    onClick = { viewModel.onSortClick(mode) }
                )
            }
            // A-Z jump pill: only where the grid is alphabetical (Albums/Artists).
            if (state.azCapable) {
                SortPill(
                    label = state.letter?.let { "A\u2013Z \u00B7 $it" } ?: "A\u2013Z",
                    selected = azOpen || state.letter != null,
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
                // "All" clears the letter without leaving the tab, so backing out
                // of a jump is one press instead of leaving the rail open.
                LetterChip(
                    label = "All",
                    selected = state.letter == null,
                    modifier = Modifier.focusRequester(railFocus),
                    onClick = {
                        azOpen = false
                        viewModel.clearLetter(null)
                        scope.launch {
                            kotlinx.coroutines.delay(150)
                            runCatching { contentFocus.requestFocus() }
                        }
                    }
                )
                AZ_LETTERS.forEach { candidate ->
                    LetterChip(
                        label = candidate,
                        selected = candidate == state.letter,
                        modifier = Modifier,
                        onClick = {
                            // A-Z only means anything on SortName, so the jump
                            // pins the sort; the key changes and page 1 reloads.
                            azOpen = false
                            viewModel.selectLetter(candidate)
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
                state.error != null && items.isEmpty() -> ErrorPane(
                    message = "Couldn't load from server",
                    detail = state.error,
                    onRetry = { viewModel.retry() }
                )
                !state.initialized && items.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    androidx.tv.material3.Text("Loading\u2026", color = TextSecondary, fontSize = 16.sp)
                }
                items.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    androidx.tv.material3.Text(
                        when {
                            state.atmosEffective && state.likedOnly -> "No liked Atmos content here"
                            state.atmosEffective && kind == "Tracks" -> "No Atmos tracks here"
                            state.atmosEffective -> "No Atmos albums here"
                            state.likedOnly -> "No liked songs yet"
                            else -> "Nothing here"
                        },
                        color = TextSecondary, fontSize = 16.sp
                    )
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
                            atmos = track.albumId?.let { it in state.atmosAlbumIds } == true,
                            onClick = { player.playQueue(items, i, resume = true) },
                            focusKeeper = focusKeeper,
                            onFocused = { focusedId = it },
                            onLongPress = { trackMenu(track) },
                            favorite = com.hans.aura.data.Favorites.isFavorite(track),
                            onToggleFavorite = {
                                scope.launch {
                                    com.hans.aura.data.Favorites.toggle(
                                        app.jellyfin.api, session.userId, track
                                    )
                                }
                            }
                        )
                    }
                    item {
                        Spacer(Modifier.height(60.dp))
                        androidx.tv.material3.Text(
                            if (items.size < state.total) "Loading more\u2026" else "${items.size} tracks",
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
                            badge = if (kind == "Albums" && item.id in state.atmosAlbumIds) "ATMOS" else null,
                            focusKeeper = focusKeeper,
                            onFocused = { focusedId = it },
                            onLongPress = if (kind != "Artists") {
                                { albumMenu(item) }
                            } else null,
                            favorite = if (kind == "Albums") {
                                com.hans.aura.data.Favorites.isFavorite(item)
                            } else null,
                            onToggleFavorite = if (kind == "Albums") {
                                {
                                    scope.launch {
                                        com.hans.aura.data.Favorites.toggle(
                                            app.jellyfin.api, session.userId, item
                                        )
                                    }
                                }
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
                                if (items.size < state.total) "Loading more\u2026"
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
    modifier: Modifier = Modifier,
    leadingIcon: Int? = null,
    trailingIcon: Int? = null
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
        val content = if (selected) androidx.compose.ui.graphics.Color(0xFF191410) else TextPrimary
        androidx.compose.foundation.layout.Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            if (leadingIcon != null) {
                androidx.compose.material3.Icon(
                    painter = androidx.compose.ui.res.painterResource(leadingIcon),
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(6.dp))
            }
            androidx.tv.material3.Text(label, fontSize = 13.sp, color = content)
            if (trailingIcon != null) {
                Spacer(Modifier.width(2.dp))
                androidx.compose.material3.Icon(
                    painter = androidx.compose.ui.res.painterResource(trailingIcon),
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(15.dp)
                )
            }
        }
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
