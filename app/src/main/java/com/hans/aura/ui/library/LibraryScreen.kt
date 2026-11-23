@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.hans.aura.ui.library

import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.tv.material3.MaterialTheme
import com.hans.aura.AuraApp
import com.hans.aura.model.Artist
import com.hans.aura.model.CollectionItem
import com.hans.aura.model.Track
import com.hans.aura.ui.common.ArtworkCard
import com.hans.aura.ui.common.AuraLoadingIndicator
import com.hans.aura.ui.common.ErrorPane
import com.hans.aura.ui.common.TrackRow
import com.hans.aura.ui.common.filterPillShape
import com.hans.aura.ui.common.requestFocusOnFrame
import com.hans.aura.ui.nav.Screen
import com.hans.aura.ui.player.PlayerConnection
import com.hans.aura.ui.theme.AuraColors
import com.hans.aura.ui.theme.AuraMotion
import com.hans.aura.ui.theme.AuraShapes
import com.hans.aura.ui.theme.AuraSizes
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
internal fun LibraryScreen(
    app: AuraApp,
    kind: String, // "Artists" | "Albums" | "Tracks" | "Playlists"
    player: PlayerConnection,
    refreshKey: Int = 0,
    focusTick: Int = 0,
    onOpenCollection: (CollectionItem) -> Unit,
    onOpenArtist: (Artist) -> Unit,
    onSwitchCategory: (Screen) -> Unit = {},
    likedOnly: Boolean = false,
    viewModel: LibraryViewModel = viewModel(
        // The tabs share a ViewModel store within the signed-in session.
        key = "library:${if (likedOnly) "Liked" else kind}:${app.jellyfin.auth.session?.userId ?: "anon"}",
        factory = viewModelFactory { initializer { LibraryViewModel(app, kind, likedOnly) } }
    )
) {
    val session = app.jellyfin.auth.session ?: return
    val state by viewModel.state.collectAsState()
    val items = state.items
    val hasMoreItems = items.size < state.total
    val tracks = items.filterIsInstance<Track>()
    val current by player.current.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(refreshKey) { viewModel.onRefreshKey(refreshKey) }
    LaunchedEffect(viewModel) { viewModel.refreshLikedIfEvicted() }

    fun trackMenu(track: Track, onRemove: (() -> Unit)? = null) =
        com.hans.aura.ui.common.ContextMenuHost.open(
            com.hans.aura.ui.common.ContextMenus.forTrack(
                app, player, scope, track,
                onOpenArtist = onOpenArtist, onOpenCollection = onOpenCollection,
                onRemoveFromPlaylist = onRemove
            )
        )

    fun albumMenu(item: CollectionItem) =
        com.hans.aura.ui.common.ContextMenuHost.open(
            com.hans.aura.ui.common.ContextMenus.forAlbum(
                app, player, scope, item, onOpenArtist = onOpenArtist
            )
        )

    var azOpen by remember { mutableStateOf(false) }

    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()
    // Covers animate only inside the visible rows plus two below, and only once
    // the grid has been still for a moment: a fling must not start (and then
    // immediately drop) a decoder per row it flies past. The process-wide
    // budget still caps the whole app at three.
    val motionWindow = com.hans.aura.ui.common.rememberGridMotionWindow(
        gridState, columns = 5
    )
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
        if (kind != "Tracks" && nearGridEnd && state.initialized && items.isNotEmpty() && hasMoreItems) {
            viewModel.loadNextPage()
        }
    }
    val canLoadLikedPage = !state.likedOnly || (!state.loading && state.error == null)
    LaunchedEffect(nearListEnd, items.size, canLoadLikedPage) {
        if (kind == "Tracks" && nearListEnd && canLoadLikedPage &&
            state.initialized && items.isNotEmpty() && hasMoreItems
        ) {
            viewModel.loadNextPage()
        }
    }
    // A-Z jump lands on the TOP of the new list; a grid still holding the
    // previous list's deep offset would drop the user in the middle of the
    // "B"s. BACK restores the same key and is deliberately left alone.
    LaunchedEffect(state.jumpTick) {
        if (state.jumpTick == 0) return@LaunchedEffect
        androidx.compose.runtime.withFrameNanos { }
        runCatching {
            if (kind == "Tracks") listState.scrollToItem(0) else gridState.scrollToItem(0)
        }
    }

    val contentFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val azPillFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val railFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val focusKeeper = remember { com.hans.aura.ui.common.FocusKeeper() }
    var focusedId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val backFocusKey = remember(focusTick) { state.cacheKey }
    com.hans.aura.ui.common.RestoreFocusOnBack(focusTick, contentFocus, focusedId, focusKeeper,
        ready = items.isNotEmpty(),
        cancelled = state.cacheKey != backFocusKey ||
            (state.initialized && !state.loading && items.isEmpty()))
    var pendingGridFocus by remember { mutableStateOf<Pair<Int, String>?>(null) }
    LibraryGridFocus(pendingGridFocus, state, contentFocus)

    Column(Modifier.fillMaxSize()) {
        if (likedOnly) {
            androidx.tv.material3.Text(
                "Liked",
                modifier = Modifier.padding(start = 32.dp, top = 24.dp, bottom = 8.dp),
                style = androidx.tv.material3.MaterialTheme.typography.headlineLarge,
                color = TextPrimary
            )
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 32.dp, end = 32.dp, top = 24.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val categories = listOf(
                    "Albums" to Screen.Albums,
                    "Artists" to Screen.Artists,
                    "Tracks" to Screen.Tracks,
                    "Playlists" to Screen.Playlists
                )
                categories.forEach { (label, screen) ->
                    SortPill(
                        label = label,
                        selected = kind == label,
                        onClick = { onSwitchCategory(screen) }
                    )
                }
            }
        }
        // Sort pills: UP from the grid reaches them; the ACTIVE pill toggles
        // its direction on click (Random has no direction).
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 32.dp, end = 32.dp, top = 4.dp, bottom = 2.dp),
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
                scope.launch { azPillFocus.requestFocusOnFrame() }
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
                        pendingGridFocus = viewModel.state.value.let { it.jumpTick to it.cacheKey }
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
                            pendingGridFocus = viewModel.state.value.let { it.jumpTick to it.cacheKey }
                        }
                    )
                }
            }
            LaunchedEffect(azOpen) {
                if (azOpen) {
                    railFocus.requestFocusOnFrame()
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
                (!state.initialized || state.loading) && items.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    AuraLoadingIndicator("Loading library")
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
                    itemsIndexed(
                        items = tracks,
                        key = { _, track -> track.id },
                        contentType = { _, _ -> "track" }
                    ) { i, track ->
                        TrackRow(
                            index = i + 1,
                            track = track,
                            isCurrent = track.id == current?.id,
                            atmos = track.albumId?.let { it in state.atmosAlbumIds } == true,
                            onClick = { player.playQueue(tracks, i, resume = true) },
                            focusKeeper = focusKeeper,
                            onFocused = { focusedId = it },
                            onLongPress = { trackMenu(track) },
                            favorite = com.hans.aura.data.Favorites.isFavorite(track),
                            onToggleFavorite = {
                                scope.launch {
                                    app.container.media.toggleFavorite(track)
                                }
                            }
                        )
                    }
                    item {
                        Spacer(Modifier.height(60.dp))
                        if (hasMoreItems && state.loading) {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                AuraLoadingIndicator("Loading more tracks")
                            }
                        } else if (!hasMoreItems) androidx.tv.material3.Text(
                            com.hans.aura.ui.common.countLabel(items.size, "track"),
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
                        .focusRequester(contentFocus)
                        .focusRestorer(),
                    contentPadding = PaddingValues(
                        horizontal = 32.dp,
                        vertical = AuraMotion.artworkFocusInset(210.dp)
                    ),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    itemsIndexed(
                        items = items,
                        key = { _, item -> "${kind}_${item.id}" },
                        contentType = { _, _ -> kind }
                    ) { index, item ->
                        ArtworkCard(
                            item = item,
                            serverUrl = session.serverUrl,
                            size = 210.dp,
                            badge = if (kind == "Albums" && item.id in state.atmosAlbumIds) "ATMOS" else null,
                            focusKeeper = focusKeeper,
                            onFocused = { focusedId = it },
                            // Only album covers have loops; and only the visible
                            // window asks. Earlier cards outrank later ones, so
                            // the budget keeps the top-left of the grid moving.
                            animate = kind == "Albums" && index in motionWindow,
                            motionPriority = -index,
                            onLongPress = if (kind != "Artists") {
                                (item as? CollectionItem)?.let { collection -> { albumMenu(collection) } }
                            } else null,
                            favorite = if (kind == "Albums") {
                                com.hans.aura.data.Favorites.isFavorite(item)
                            } else null,
                            onToggleFavorite = if (kind == "Albums") {
                                {
                                    scope.launch {
                                        app.container.media.toggleFavorite(item)
                                    }
                                }
                            } else null
                        ) {
                            when (kind) {
                                "Artists" -> (item as? Artist)?.let(onOpenArtist)
                                else -> (item as? CollectionItem)?.let(onOpenCollection)
                            }
                        }
                    }
                    item(span = { GridItemSpan(5) }) {
                        Column {
                            Spacer(Modifier.height(24.dp))
                            if (hasMoreItems && state.loading) {
                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    AuraLoadingIndicator("Loading more library items")
                                }
                            } else if (!hasMoreItems) androidx.tv.material3.Text(
                                com.hans.aura.ui.common.countLabel(
                                    items.size,
                                    singular = kind.lowercase().removeSuffix("s"),
                                    plural = kind.lowercase()
                                ),
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
    val colors = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    val shape = filterPillShape(selected, focused)
    val fill by animateColorAsState(
        if (focused) AuraColors.FocusedFillSubtle else Color.Transparent,
        animationSpec = AuraMotion.scheme.fastEffectsSpec(),
        label = "sortPillFill"
    )
    Box(
        modifier
            .semantics { this.selected = selected }
            .clip(shape)
            .background(if (selected) colors.primary else fill)
            .border(
                width = AuraSizes.focusBorderLoud,
                color = if (focused) AuraColors.CursorRing else Color.Transparent,
                shape = shape
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        val content = if (selected) colors.onPrimary else colors.onSurface
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

@Composable
internal fun LibraryGridFocus(
    pick: Pair<Int, String>?,
    state: LibraryUiState,
    requester: androidx.compose.ui.focus.FocusRequester
) {
    androidx.compose.runtime.key(pick) {
        com.hans.aura.ui.common.InitialFocusOnPush(
            shouldFocus = pick != null,
            requester = requester,
            ready = state.items.isNotEmpty(),
            cancelled = pick != (state.jumpTick to state.cacheKey) ||
                (state.initialized && !state.loading && state.items.isEmpty())
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
    val shape = AuraShapes.control
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
