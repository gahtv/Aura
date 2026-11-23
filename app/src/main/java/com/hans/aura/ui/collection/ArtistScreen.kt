@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.hans.aura.ui.collection

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.tv.material3.MaterialTheme
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.hans.aura.AuraApp
import com.hans.aura.data.ArtistArtwork
import com.hans.aura.data.AuthStore
import com.hans.aura.data.JellyfinItem
import com.hans.aura.data.Mixes
import com.hans.aura.data.artistArtwork
import com.hans.aura.ui.common.ArtworkCard
import com.hans.aura.ui.common.ErrorPane
import com.hans.aura.ui.common.TrackRow
import com.hans.aura.ui.player.PlayerConnection
import com.hans.aura.ui.theme.AuraColors
import com.hans.aura.ui.theme.AuraMotion
import com.hans.aura.ui.theme.AuraShapes
import com.hans.aura.ui.theme.AuraSizes
import com.hans.aura.ui.theme.AuraSpacing
import com.hans.aura.ui.theme.Canvas
import com.hans.aura.ui.theme.Surface
import com.hans.aura.ui.theme.SurfaceHigh
import com.hans.aura.ui.theme.TextPrimary
import com.hans.aura.ui.theme.TextSecondary
import com.hans.aura.ui.theme.darken
import com.hans.aura.ui.theme.rememberDynamicColors
import kotlinx.coroutines.launch

private enum class ArtistTab(val label: String) {
    DISCOGRAPHY("Discography"),
    POPULAR("Popular"),
    SIMILAR("Similar Artists")
}

/** Artist page: 2-column split with fixed artist pane + tabbed right pane. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ArtistScreen(
    app: AuraApp,
    artist: JellyfinItem,
    player: PlayerConnection,
    onBack: () -> Unit,
    focusTick: Int = 0,
    initialFocus: Boolean = false,
    onOpenCollection: (JellyfinItem) -> Unit,
    onOpenArtist: (JellyfinItem) -> Unit = {},
    viewModel: ArtistViewModel = viewModel(
        key = "artist:${artist.id}:${app.jellyfin.auth.session?.userId ?: "anon"}",
        factory = viewModelFactory { initializer { ArtistViewModel(app, artist.id) } }
    )
) {
    val session = app.jellyfin.auth.session ?: return
    val state by viewModel.state.collectAsState()
    val current by player.current.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val shownArtist = state.shown(artist)
    val artwork = shownArtist.artistArtwork(session.serverUrl)
    val artUrl = shownArtist.primaryImageTag?.let {
        AuthStore.imageUrl(session.serverUrl, shownArtist.id, it, maxWidth = 480)
    }
    val dyn = rememberDynamicColors(artUrl)
    val accent = dyn?.secondary ?: com.hans.aura.ui.theme.Accent
    val atmosAlbumIds = state.atmosAlbumIds
    var showBioDialog by remember { mutableStateOf(false) }
    var sourceBio by remember(artist.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(artist.id) {
        sourceBio = runCatching { com.hans.aura.data.MusicSources.current?.artistBio(artist.id) }
            .getOrNull()
    }
    val bio = sourceBio?.takeIf { it.isNotBlank() } ?: state.overview

    val contentFocus = remember { FocusRequester() }
    val leftFocus = remember { FocusRequester() }
    val focusKeeper = remember { com.hans.aura.ui.common.FocusKeeper() }
    var focusedId by rememberSaveable { mutableStateOf<String?>(null) }
    var lastFocusSection by remember { mutableStateOf("") }
    fun logFocus(section: String, id: String) {
        if (lastFocusSection == section) return
        lastFocusSection = section
        android.util.Log.d("AURA_ARTIST", "focus=$section id=$id artist=${artist.id}")
    }
    val visibleAlbums = state.visibleAlbums
    val visibleTracks = state.visibleTopTracks
    val similarArtists = state.similar.orEmpty()

    val availableTabs = remember(visibleAlbums, visibleTracks, similarArtists) {
        buildList {
            if (visibleAlbums.isNotEmpty() || (visibleTracks.isEmpty() && similarArtists.isEmpty())) {
                add(ArtistTab.DISCOGRAPHY)
            }
            if (visibleTracks.isNotEmpty()) add(ArtistTab.POPULAR)
            if (similarArtists.isNotEmpty()) add(ArtistTab.SIMILAR)
        }
    }
    var selectedTab by rememberSaveable(artist.id) { mutableStateOf(ArtistTab.DISCOGRAPHY) }
    val activeTab = if (selectedTab in availableTabs) selectedTab else availableTabs.first()
    val contentReady = when (activeTab) {
        ArtistTab.DISCOGRAPHY -> visibleAlbums.isNotEmpty()
        ArtistTab.POPULAR -> visibleTracks.isNotEmpty()
        ArtistTab.SIMILAR -> similarArtists.isNotEmpty()
    }
    val tabLoaded = when (activeTab) {
        ArtistTab.DISCOGRAPHY -> state.albums != null
        ArtistTab.POPULAR -> state.topTracks != null
        ArtistTab.SIMILAR -> state.similar != null
    }
    val emptyResult = !contentReady && (tabLoaded || state.error != null)
    if (!initialFocus) {
        com.hans.aura.ui.common.RestoreFocusOnBack(focusTick, contentFocus, focusedId, focusKeeper,
            ready = contentReady, cancelled = emptyResult)
    }
    com.hans.aura.ui.common.InitialFocusOnPush(initialFocus, contentFocus,
        ready = contentReady, cancelled = emptyResult)

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        dyn?.primary ?: Surface,
                        dyn?.let { darken(it.primary, 0.55f) } ?: SurfaceHigh,
                        Canvas
                    )
                )
            )
    ) {
        artwork.bannerUrl?.let { url ->
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = 0.22f,
                modifier = Modifier.fillMaxSize()
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0.0f to (dyn?.primary ?: Surface).copy(alpha = 0.80f),
                        0.45f to Canvas.copy(alpha = 0.86f),
                        1.0f to Canvas.copy(alpha = 0.94f)
                    )
                )
        )

        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = AuraSpacing.screenHorizontal, vertical = AuraSpacing.lg),
            horizontalArrangement = Arrangement.spacedBy(AuraSpacing.xl)
        ) {
            // Left pane (~35%): circular portrait, title/logo, bio, and actions.
            Column(
                modifier = Modifier
                    .weight(0.35f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.Top
            ) {
                if (artUrl != null) {
                    AsyncImage(
                        model = artUrl,
                        contentDescription = shownArtist.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(180.dp)
                            .clip(CircleShape)
                            .background(SurfaceHigh)
                    )
                    Spacer(Modifier.height(AuraSpacing.md))
                }
                ArtistTitle(shownArtist.name, artwork.logoUrl)
                bio?.let { text ->
                    Row(
                        Modifier
                            .padding(top = AuraSpacing.sm)
                            .fillMaxWidth()
                            .clickable { showBioDialog = true },
                        verticalAlignment = Alignment.Top
                    ) {
                        androidx.tv.material3.Text(
                            text,
                            fontSize = 13.sp,
                            color = TextPrimary,
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        androidx.tv.material3.Text(
                            "  More",
                            fontSize = 13.sp,
                            color = accent
                        )
                    }
                }
                Spacer(Modifier.height(AuraSpacing.md))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(AuraSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(AuraSpacing.sm)
                ) {
                    ArtistRadioButton(
                        accent = accent,
                        modifier = Modifier
                            .focusRequester(leftFocus)
                            .focusProperties { right = contentFocus },
                        onClick = {
                            scope.launch {
                                Mixes.playMix(
                                    api = app.jellyfin.api,
                                    userId = session.userId,
                                    player = player,
                                    kind = Mixes.Kind.ARTIST,
                                    seed = shownArtist
                                )
                            }
                        }
                    )
                    if (!com.hans.aura.data.AtmosFilter.on) {
                        com.hans.aura.ui.common.AtmosFilterPill(
                            selected = state.atmosView,
                            accent = accent,
                            onClick = { viewModel.toggleAtmos() }
                        )
                    }
                    androidx.tv.material3.Button(
                        modifier = Modifier.focusProperties { right = contentFocus },
                        onClick = onBack
                    ) {
                        androidx.tv.material3.Text("Back")
                    }
                }
            }

            // Right pane (~65%): sub-filter pills + content list/grid.
            Column(
                modifier = Modifier
                    .weight(0.65f)
                    .fillMaxHeight()
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(AuraSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    availableTabs.forEach { tab ->
                        ArtistTabPill(
                            label = tab.label,
                            selected = tab == activeTab,
                            accent = accent,
                            onClick = { selectedTab = tab }
                        )
                    }
                }
                Spacer(Modifier.height(AuraSpacing.md))

                if (state.error != null && !state.hasContent) {
                    ErrorPane(
                        message = "Couldn't load from server",
                        detail = state.error,
                        onRetry = { viewModel.retry() }
                    )
                } else {
                    when (activeTab) {
                        ArtistTab.DISCOGRAPHY -> LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            modifier = Modifier
                                .fillMaxSize()
                                .focusRequester(contentFocus)
                                .focusRestorer()
                                .focusProperties { left = leftFocus },
                            contentPadding = PaddingValues(
                                vertical = AuraMotion.artworkFocusInset(176.dp),
                                horizontal = AuraSpacing.xs
                            ),
                            horizontalArrangement = Arrangement.spacedBy(AuraSpacing.md),
                            verticalArrangement = Arrangement.spacedBy(AuraSpacing.lg)
                        ) {
                            items(
                                items = visibleAlbums,
                                key = { "album_${it.id}" },
                                contentType = { "album" }
                            ) { album ->
                                ArtworkCard(
                                    item = album,
                                    serverUrl = session.serverUrl,
                                    size = 176.dp,
                                    badge = "ATMOS".takeIf { album.id in atmosAlbumIds },
                                    badgeColor = accent,
                                    accent = accent,
                                    focusKeeper = focusKeeper,
                                    onFocused = {
                                        focusedId = it
                                        logFocus("albums", it)
                                    }
                                ) { onOpenCollection(album) }
                            }
                            item { Spacer(Modifier.height(AuraSizes.bottomBarClearance)) }
                        }

                        ArtistTab.POPULAR -> LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .focusRequester(contentFocus)
                                .focusRestorer()
                                .focusProperties { left = leftFocus },
                            contentPadding = PaddingValues(bottom = AuraSizes.bottomBarClearance)
                        ) {
                            itemsIndexed(
                                items = visibleTracks,
                                key = { _, t -> "top_${t.id}" },
                                contentType = { _, _ -> "track" }
                            ) { i, track ->
                                TrackRow(
                                    index = i + 1,
                                    track = track,
                                    isCurrent = track.id == current?.id,
                                    atmos = track.albumId?.let { it in atmosAlbumIds } == true,
                                    accent = accent,
                                    onClick = { player.playQueue(visibleTracks, i, resume = true) },
                                    focusKeeper = focusKeeper,
                                    onFocused = {
                                        focusedId = it
                                        logFocus("top", it)
                                    },
                                    onLongPress = {
                                        com.hans.aura.ui.common.ContextMenuHost.open(
                                            com.hans.aura.ui.common.ContextMenus.forTrack(
                                                app, player, scope, track,
                                                onOpenArtist = null, onOpenCollection = onOpenCollection
                                            )
                                        )
                                    }
                                )
                            }
                        }

                        ArtistTab.SIMILAR -> LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            modifier = Modifier
                                .fillMaxSize()
                                .focusRequester(contentFocus)
                                .focusRestorer()
                                .focusProperties { left = leftFocus },
                            contentPadding = PaddingValues(
                                vertical = AuraMotion.artworkFocusInset(176.dp),
                                horizontal = AuraSpacing.xs
                            ),
                            horizontalArrangement = Arrangement.spacedBy(AuraSpacing.md),
                            verticalArrangement = Arrangement.spacedBy(AuraSpacing.lg)
                        ) {
                            items(
                                items = similarArtists,
                                key = { "similar_${it.id}" },
                                contentType = { "artist" }
                            ) { similar ->
                                ArtworkCard(
                                    item = similar,
                                    serverUrl = session.serverUrl,
                                    size = 176.dp,
                                    accent = accent,
                                    focusKeeper = focusKeeper,
                                    onFocused = {
                                        focusedId = it
                                        logFocus("similar", it)
                                    }
                                ) { onOpenArtist(similar) }
                            }
                            item { Spacer(Modifier.height(AuraSizes.bottomBarClearance)) }
                        }
                    }
                }
            }
        }

        val shownBio = bio
        if (showBioDialog && shownBio != null) {
            com.hans.aura.ui.common.BioDialog(
                title = shownArtist.name,
                subtitle = "Artist",
                text = shownBio,
                onDismiss = { showBioDialog = false }
            )
        }
    }
}

@Composable
private fun ArtistTabPill(
    label: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = AuraShapes.pill
    val bg = when {
        selected -> accent
        focused -> AuraColors.FocusedFill
        else -> AuraColors.PillBackground
    }
    val borderColor = when {
        focused -> TextPrimary
        selected -> Color.Transparent
        else -> AuraColors.FocusedFillSubtle
    }
    Box(
        Modifier
            .clip(shape)
            .background(bg)
            .border(if (focused) AuraSizes.focusBorder else AuraSizes.hairline, borderColor, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() }
            .padding(horizontal = AuraSpacing.md, vertical = AuraSpacing.xs)
    ) {
        androidx.tv.material3.Text(
            text = label,
            color = if (selected) collectionPlayContentColor(accent) else TextPrimary,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun ArtistTitle(name: String, logoUrl: String?) {
    if (logoUrl != null) {
        val context = LocalContext.current
        val request = remember(context, logoUrl) {
            ImageRequest.Builder(context)
                .data(logoUrl)
                .size(640, 160)
                .bitmapConfig(android.graphics.Bitmap.Config.ARGB_8888)
                .build()
        }
        val painter = rememberAsyncImagePainter(request)
        if (painter.state is AsyncImagePainter.State.Success) {
            Image(
                painter = painter,
                contentDescription = name,
                contentScale = ContentScale.Fit,
                alignment = Alignment.CenterStart,
                modifier = Modifier.widthIn(max = 320.dp).height(64.dp)
            )
            return
        }
    }
    androidx.tv.material3.Text(name, style = MaterialTheme.typography.headlineLarge, color = TextPrimary)
}

@Composable
private fun ArtistRadioButton(
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = AuraShapes.control
    Box(
        modifier
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() }
            .clip(shape)
            .background(if (focused) TextPrimary else accent)
            .border(3.dp, if (focused) TextPrimary else Color.Transparent, shape)
            .padding(horizontal = 18.dp, vertical = 10.dp)
    ) {
        androidx.tv.material3.Text(
            "Radio",
            color = if (focused) Canvas else collectionPlayContentColor(accent),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
