@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.hans.aura.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.tv.material3.MaterialTheme
import coil.compose.AsyncImage
import com.hans.aura.AuraApp
import com.hans.aura.data.AtmosFilter
import com.hans.aura.data.JellyfinItem
import com.hans.aura.ui.common.ErrorPane
import com.hans.aura.ui.common.FocusKeeper
import com.hans.aura.ui.common.ItemShelf
import com.hans.aura.ui.common.RestoreFocusOnBack
import com.hans.aura.ui.common.contextMenuKeys
import com.hans.aura.ui.common.rememberAtmosAlbumIds
import com.hans.aura.ui.common.listMotionWindow
import com.hans.aura.ui.common.resolveArtwork
import com.hans.aura.ui.nowplaying.AtmosBadge
import com.hans.aura.ui.player.PlayerConnection
import com.hans.aura.ui.theme.Accent
import com.hans.aura.ui.theme.AuraColors
import com.hans.aura.ui.theme.AuraMotion
import com.hans.aura.ui.theme.AuraShapes
import com.hans.aura.ui.theme.AuraSizes
import com.hans.aura.ui.theme.AuraSpacing
import com.hans.aura.ui.theme.AuraTypography
import com.hans.aura.ui.theme.Canvas
import com.hans.aura.ui.theme.Surface
import com.hans.aura.ui.theme.SurfaceHigh
import com.hans.aura.ui.theme.TextPrimary
import com.hans.aura.ui.theme.TextSecondary
import com.hans.aura.ui.theme.rememberDynamicColors
import kotlinx.coroutines.flow.first

private data class HomeRow(
    val key: String,
    val contentType: String,
    val items: List<JellyfinItem>?,
    val content: @Composable LazyItemScope.(Int) -> Unit
)

@Composable
fun HomeScreen(
    app: AuraApp,
    player: PlayerConnection,
    onSignOut: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenCollection: (JellyfinItem) -> Unit,
    onOpenArtist: (JellyfinItem) -> Unit,
    onPlayTrack: (track: JellyfinItem, queue: List<JellyfinItem>, index: Int) -> Unit,
    refreshKey: Int = 0,
    focusTick: Int = 0,
    viewModel: HomeViewModel = viewModel(
        key = "home:${app.jellyfin.auth.session?.userId ?: "anon"}",
        factory = viewModelFactory { initializer { HomeViewModel(app) } }
    )
) {
    val session = app.jellyfin.auth.session ?: return
    val serverUrl = session.serverUrl
    val state by viewModel.state.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val atmosAlbumIds = rememberAtmosAlbumIds(app.jellyfin.api, session.userId)

    LaunchedEffect(refreshKey) { viewModel.onRefreshKey(refreshKey) }

    val quickAccessItems = remember(state) {
        state.quickAccess.filter { AtmosFilter.keep(it) }
    }
    val itemsById = remember(state) {
        buildMap {
            listOf(
                state.recentlyPlayed,
                state.recentArtists,
                state.recentAlbums,
                state.atmosAlbums,
                state.artists,
                state.playlists
            ).forEach { list ->
                list?.forEach { put(it.id, it) }
            }
        }
    }
    var previewItem by remember { mutableStateOf<JellyfinItem?>(null) }
    val activePreview = previewItem ?: quickAccessItems.firstOrNull() ?: state.recentAlbums?.firstOrNull()

    val contentFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val focusKeeper = remember { FocusKeeper() }
    var focusedCardId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var focusedShelfKey by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var savedShelfIndex by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(0) }
    var savedShelfOffset by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(0) }
    val listState = rememberLazyListState()
    val motionWindow by remember(listState) {
        derivedStateOf { listMotionWindow(listState.layoutInfo) }
    }

    fun saveShelfPosition() {
        listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == focusedShelfKey }?.let {
            savedShelfIndex = it.index
            savedShelfOffset = it.offset
        }
    }

    fun trackMenu(track: JellyfinItem) {
        saveShelfPosition()
        com.hans.aura.ui.common.ContextMenuHost.open(
            com.hans.aura.ui.common.ContextMenus.forTrack(
                app, player, scope, track,
                onOpenArtist = onOpenArtist, onOpenCollection = onOpenCollection
            )
        )
    }

    fun albumMenu(item: JellyfinItem) {
        saveShelfPosition()
        com.hans.aura.ui.common.ContextMenuHost.open(
            com.hans.aura.ui.common.ContextMenus.forAlbum(
                app, player, scope, item, onOpenArtist = onOpenArtist
            )
        )
    }

    fun openItem(item: JellyfinItem, shelfList: List<JellyfinItem>?, idx: Int) {
        saveShelfPosition()
        when (item.type) {
            "MusicArtist" -> onOpenArtist(item)
            "Audio" -> onPlayTrack(item, shelfList ?: listOf(item), idx.coerceAtLeast(0))
            else -> onOpenCollection(item)
        }
    }

    fun onItemFocused(id: String, shelf: String) {
        focusedCardId = id
        focusedShelfKey = shelf
        itemsById[id.removePrefix("qa:")]?.let { previewItem = it }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(top = AuraSpacing.xs)
            .focusRequester(contentFocus)
            .focusRestorer()
    ) {
        if (!state.loading && state.error != null && state.allShelvesEmpty) {
            ErrorPane(
                message = "Couldn't load from server",
                detail = state.error,
                onRetry = { viewModel.retry() }
            )
            return@Column
        }

        val rows = buildList {
            fun homeItem(
                key: String,
                contentType: String,
                items: List<JellyfinItem>? = null,
                content: @Composable LazyItemScope.(Int) -> Unit
            ) {
                add(HomeRow(key, contentType, items, content))
            }

            if (!state.loading && state.error != null) {
                homeItem(key = "partialError", contentType = "error") {
                    ErrorPane(
                        message = "Some shelves couldn't load",
                        onRetry = { viewModel.retry() },
                        modifier = Modifier.height(200.dp)
                    )
                }
            }

            if (activePreview != null) {
                homeItem(key = "hero", contentType = "hero") {
                    HomeLivePreviewHero(
                        item = activePreview,
                        serverUrl = serverUrl,
                        isAtmos = activePreview.id in atmosAlbumIds || activePreview.albumId in atmosAlbumIds
                    )
                    Spacer(Modifier.height(AuraSpacing.md))
                }
            }

            if (quickAccessItems.isNotEmpty()) {
                homeItem(key = "quickAccess", contentType = "quickAccess", items = quickAccessItems) {
                    QuickAccessGrid(
                        items = quickAccessItems,
                        serverUrl = serverUrl,
                        atmosAlbumIds = atmosAlbumIds,
                        focusKeeper = focusKeeper,
                        onFocused = { onItemFocused(it, "quickAccess") },
                        onOpen = { item -> openItem(item, listOf(item), 0) },
                        onLongPress = { item ->
                            if (item.type == "Audio") trackMenu(item)
                            else if (item.type != "MusicArtist") albumMenu(item)
                        }
                    )
                    Spacer(Modifier.height(AuraSpacing.lg))
                }
            }

            // History shelves only exist on a backend that records play history.
            if (!state.recentlyPlayed.isNullOrEmpty()) {
                homeItem(key = "shelf_recent_played", contentType = "shelf", items = state.recentlyPlayed) { index ->
                    MotionShelf(
                        animate = index in motionWindow,
                        title = "Recently played",
                        items = state.recentlyPlayed,
                        showLoading = state.loading,
                        serverUrl = serverUrl,
                        focusKeeper = focusKeeper,
                        onFocused = { onItemFocused(it, "shelf_recent_played") },
                        onLongPress = { trackMenu(it) },
                        onOpen = { item, idx ->
                            saveShelfPosition()
                            onPlayTrack(item, state.recentlyPlayed ?: listOf(item), idx)
                        }
                    )
                }
            }
            if (!state.recentArtists.isNullOrEmpty()) {
                homeItem(key = "shelf_recent_artists", contentType = "shelf", items = state.recentArtists) { index ->
                    MotionShelf(
                        animate = index in motionWindow,
                        title = "Recently played artists",
                        items = state.recentArtists,
                        showLoading = state.loading,
                        serverUrl = serverUrl,
                        focusKeeper = focusKeeper,
                        onFocused = { onItemFocused(it, "shelf_recent_artists") },
                        onOpen = { item, _ -> saveShelfPosition(); onOpenArtist(item) }
                    )
                }
            }
            homeItem(key = "shelf_recent_albums", contentType = "shelf", items = state.recentAlbums) { index ->
                MotionShelf(
                    animate = index in motionWindow,
                    title = "Recently added",
                    items = state.recentAlbums,
                    showLoading = state.loading,
                    serverUrl = serverUrl,
                    focusKeeper = focusKeeper,
                    onFocused = { onItemFocused(it, "shelf_recent_albums") },
                    onLongPress = { albumMenu(it) },
                    onOpen = { item, _ -> saveShelfPosition(); onOpenCollection(item) }
                )
            }
            homeItem(key = "shelf_atmos", contentType = "shelf", items = state.atmosAlbums) { index ->
                MotionShelf(
                    animate = index in motionWindow,
                    title = "Your Atmos",
                    items = state.atmosAlbums,
                    showLoading = state.loading,
                    serverUrl = serverUrl,
                    focusKeeper = focusKeeper,
                    onFocused = { onItemFocused(it, "shelf_atmos") },
                    badgeFor = { "ATMOS" },
                    onLongPress = { albumMenu(it) },
                    onOpen = { item, _ -> saveShelfPosition(); onOpenCollection(item) }
                )
            }
            homeItem(key = "shelf_artists", contentType = "shelf", items = state.artists) { index ->
                MotionShelf(
                    animate = index in motionWindow,
                    title = "Artists",
                    items = state.artists,
                    showLoading = state.loading,
                    serverUrl = serverUrl,
                    focusKeeper = focusKeeper,
                    onFocused = { onItemFocused(it, "shelf_artists") },
                    onOpen = { item, _ -> saveShelfPosition(); onOpenArtist(item) }
                )
            }
            if (!state.playlists.isNullOrEmpty()) {
                homeItem(key = "shelf_playlists", contentType = "shelf", items = state.playlists) { index ->
                    MotionShelf(
                        animate = index in motionWindow,
                        title = "Playlists",
                        items = state.playlists,
                        showLoading = state.loading,
                        serverUrl = serverUrl,
                        focusKeeper = focusKeeper,
                        onFocused = { onItemFocused(it, "shelf_playlists") },
                        onLongPress = { albumMenu(it) },
                        onOpen = { item, _ -> saveShelfPosition(); onOpenCollection(item) }
                    )
                }
            }
            homeItem(key = "bottomSpacer", contentType = "spacer") {
                Spacer(Modifier.height(140.dp)) // clear of the now-playing bar
            }
        }
        val savedCardAvailable = rows.firstOrNull { it.key == focusedShelfKey }?.items.orEmpty()
            .any { it.id == focusedCardId?.removePrefix("qa:") && AtmosFilter.keep(it) }
        val shelfReady = homeShelfReady(focusTick, listState, rows.map { it.key }, focusedShelfKey,
            savedShelfIndex, savedShelfOffset, savedCardAvailable)
        RestoreFocusOnBack(focusTick, contentFocus, focusedCardId, focusKeeper,
            ready = (quickAccessItems.isNotEmpty() || itemsById.values.any { AtmosFilter.keep(it) }) &&
                (focusedCardId == null || (savedCardAvailable && shelfReady) ||
                    (!savedCardAvailable && !state.loading)),
            cancelled = !state.loading && quickAccessItems.isEmpty() &&
                itemsById.values.none { AtmosFilter.keep(it) })
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(rows, key = { _, row -> row.key }, contentType = { _, row -> row.contentType }) { index, row ->
                row.content(this, index)
            }
        }
    }
}

@Composable
internal fun homeShelfReady(
    focusTick: Int,
    listState: LazyListState,
    rowKeys: List<String>,
    shelfKey: String?,
    savedIndex: Int,
    savedOffset: Int,
    cardAvailable: Boolean
): Boolean {
    var ready by remember(focusTick) { mutableStateOf(focusTick <= 0) }
    LaunchedEffect(focusTick, rowKeys, shelfKey, cardAvailable) {
        val index = rowKeys.indexOf(shelfKey)
        if (ready || !cardAvailable || index < 0) return@LaunchedEffect
        val layout = snapshotFlow { listState.layoutInfo }.first {
            it.totalItemsCount == rowKeys.size && it.visibleItemsInfo.isNotEmpty()
        }
        if (index != savedIndex || layout.visibleItemsInfo.none { it.key == shelfKey }) {
            listState.scrollToItem(index, -savedOffset)
        }
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.any { it.key == shelfKey } }.first { it }
        ready = true
    }
    return ready
}

/**
 * Non-focusable live-preview hero banner at the top of Home: crossfades
 * backdrop art, metadata, and subtle tonal wash as D-pad focus moves.
 */
@Composable
private fun HomeLivePreviewHero(
    item: JellyfinItem,
    serverUrl: String,
    isAtmos: Boolean
) {
    val art = item.resolveArtwork()
    val artUrl = art?.url(serverUrl, maxWidth = 720)
    val dyn = rememberDynamicColors(art?.url(serverUrl, maxWidth = 240))
    val wash by animateColorAsState(
        targetValue = dyn?.primary ?: SurfaceHigh,
        animationSpec = spring(stiffness = 800f),
        label = "heroWash"
    )
    val accent = dyn?.secondary ?: Accent

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AuraSpacing.screenHorizontal)
            .height(184.dp)
            .clip(AuraShapes.cardHero)
            .background(Brush.horizontalGradient(listOf(wash, Surface)))
            .border(AuraSizes.hairline, AuraColors.FocusedFillSubtle, AuraShapes.cardHero)
    ) {
        AnimatedContent(
            targetState = artUrl,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "heroArt",
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxWidth(0.55f)
                .fillMaxHeight()
        ) { url ->
            if (url != null) {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        // Horizontal readability scrim so bright covers never wash out text.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0.0f to wash,
                        0.45f to wash.copy(alpha = 0.92f),
                        0.75f to Canvas.copy(alpha = 0.55f),
                        1.0f to Canvas.copy(alpha = 0.25f)
                    )
                )
        )
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(0.68f)
                .padding(horizontal = AuraSpacing.xl, vertical = AuraSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AuraSpacing.xs)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AuraSpacing.sm)
            ) {
                val typeLabel = when (item.type) {
                    "MusicAlbum" -> item.year?.toString()?.let { "ALBUM \u00B7 $it" } ?: "ALBUM"
                    "MusicArtist" -> "ARTIST"
                    "Playlist" -> "PLAYLIST"
                    "Audio" -> "TRACK"
                    else -> item.type.uppercase()
                }
                androidx.tv.material3.Text(
                    text = typeLabel,
                    color = accent,
                    fontSize = AuraTypography.caption,
                    fontWeight = FontWeight.Bold
                )
                if (isAtmos) {
                    AtmosBadge(color = accent)
                }
            }
            androidx.tv.material3.Text(
                text = item.name,
                style = MaterialTheme.typography.headlineLarge,
                color = TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val subtitle = when (item.type) {
                "MusicAlbum" -> item.albumArtist ?: item.artistNames
                "Audio" -> listOfNotNull(item.artistNames.takeIf { it.isNotBlank() }, item.album).joinToString(" \u00B7 ")
                "Playlist" -> item.childCount?.let { "$it tracks" } ?: "Playlist"
                else -> ""
            }
            if (subtitle.isNotBlank()) {
                androidx.tv.material3.Text(
                    text = subtitle,
                    color = TextSecondary,
                    fontSize = AuraTypography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** Compact 2x4 Quick-Access shortcut grid below the Home hero. */
@Composable
private fun QuickAccessGrid(
    items: List<JellyfinItem>,
    serverUrl: String,
    atmosAlbumIds: Set<String>,
    focusKeeper: FocusKeeper,
    onFocused: (String) -> Unit,
    onOpen: (JellyfinItem) -> Unit,
    onLongPress: (JellyfinItem) -> Unit
) {
    val rows = items.take(8).chunked(4)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AuraSpacing.screenHorizontal),
        verticalArrangement = Arrangement.spacedBy(AuraSpacing.sm)
    ) {
        rows.forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AuraSpacing.sm)
            ) {
                rowItems.forEach { item ->
                    QuickAccessTile(
                        item = item,
                        serverUrl = serverUrl,
                        isAtmos = item.id in atmosAlbumIds || item.albumId in atmosAlbumIds,
                        focusKeeper = focusKeeper,
                        onFocused = { onFocused("qa:${item.id}") },
                        onLongPress = { onLongPress(item) },
                        onClick = { onOpen(item) },
                        modifier = Modifier.weight(1f)
                    )
                }
                repeat(4 - rowItems.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun QuickAccessTile(
    item: JellyfinItem,
    serverUrl: String,
    isAtmos: Boolean,
    focusKeeper: FocusKeeper,
    onFocused: () -> Unit,
    onLongPress: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.03f else 1f,
        animationSpec = AuraMotion.spatialFast,
        label = "quickTileScale"
    )
    val bg by animateColorAsState(
        targetValue = if (focused) SurfaceHigh else Surface,
        animationSpec = spring(stiffness = 1600f),
        label = "quickTileBg"
    )
    val thumbShape = if (item.type == "MusicArtist") CircleShape else RoundedCornerShape(8.dp)
    val artUrl = item.resolveArtwork()?.url(serverUrl, maxWidth = 160)

    Row(
        modifier = modifier
            .height(64.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(AuraShapes.quickTile)
            .background(bg)
            .then(
                if (focused) Modifier.border(AuraSizes.focusBorder, Accent, AuraShapes.quickTile)
                else Modifier.border(AuraSizes.hairline, AuraColors.FocusedFillSubtle, AuraShapes.quickTile)
            )
            .focusRequester(focusKeeper.requesterFor("qa:${item.id}"))
            .contextMenuKeys(onLongPress)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clickable { onClick() }
            .padding(AuraSpacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (artUrl != null) {
            AsyncImage(
                model = artUrl,
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(48.dp)
                    .clip(thumbShape)
                    .background(SurfaceHigh)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(thumbShape)
                    .background(SurfaceHigh),
                contentAlignment = Alignment.Center
            ) {
                androidx.tv.material3.Text(
                    item.name.take(1).uppercase(),
                    color = TextSecondary,
                    fontSize = AuraTypography.subtitle
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = AuraSpacing.sm)
        ) {
            androidx.tv.material3.Text(
                text = item.name,
                color = TextPrimary,
                fontSize = AuraTypography.bodySmall,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (isAtmos) {
            Box(
                Modifier
                    .padding(end = AuraSpacing.xs)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Accent)
            )
        }
    }
}

@Composable
private fun MotionShelf(
    animate: Boolean,
    title: String,
    items: List<JellyfinItem>?,
    showLoading: Boolean,
    serverUrl: String,
    focusKeeper: FocusKeeper,
    onFocused: (String) -> Unit,
    onOpen: (JellyfinItem, Int) -> Unit,
    badgeFor: ((JellyfinItem) -> String?)? = null,
    onLongPress: ((JellyfinItem) -> Unit)? = null
) {
    ItemShelf(
        title = title,
        items = items,
        showLoading = showLoading,
        serverUrl = serverUrl,
        focusKeeper = focusKeeper,
        onFocused = onFocused,
        onOpen = onOpen,
        badgeFor = badgeFor,
        onLongPress = onLongPress,
        animate = animate
    )
}
