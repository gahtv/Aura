@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.hans.aura.ui.collection

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import com.hans.aura.data.Mixes
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hans.aura.data.AuthStore
import com.hans.aura.ui.theme.darken
import com.hans.aura.ui.theme.rememberDynamicColors
import androidx.compose.ui.unit.sp
import com.hans.aura.AuraApp
import com.hans.aura.data.JellyfinItem
import com.hans.aura.ui.common.ArtworkCard
import com.hans.aura.ui.common.ErrorPane
import com.hans.aura.ui.common.TrackRow
import com.hans.aura.ui.player.PlayerConnection
import com.hans.aura.ui.theme.Surface
import com.hans.aura.ui.theme.TextPrimary
import com.hans.aura.ui.theme.TextSecondary

/** Artist page: albums grid, similar artists, top tracks. */
@Composable
fun ArtistScreen(
    app: AuraApp,
    artist: JellyfinItem,
    player: PlayerConnection,
    onBack: () -> Unit,
    focusTick: Int = 0,
    initialFocus: Boolean = false,
    onOpenCollection: (JellyfinItem) -> Unit,
    // Similar artists open as another artist page (same screen, pushed).
    onOpenArtist: (JellyfinItem) -> Unit = {},
    // Keyed per artist AND per session: the Activity owns the ViewModelStore
    // (AppRoot's back-stack is manual), so a key collision would serve one
    // artist's albums on another's page.
    viewModel: ArtistViewModel = viewModel(
        key = "artist:${artist.id}:${app.jellyfin.auth.session?.userId ?: "anon"}",
        factory = viewModelFactory { initializer { ArtistViewModel(app, artist.id) } }
    )
) {
    val session = app.jellyfin.auth.session ?: return
    val state by viewModel.state.collectAsState()
    val current by player.current.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // Dynamic color wash from the artist image, kept dark for text contrast.
    // The hydrated item is preferred: a stub artist (id + name only) carries no
    // PrimaryImageTag, so the palette would have nothing to read.
    val shownArtist = state.fetchedArtist ?: artist
    val artUrl = shownArtist.primaryImageTag?.let {
        AuthStore.imageUrl(session.serverUrl, shownArtist.id, it, maxWidth = 480)
    }
    val dyn = rememberDynamicColors(artUrl)
    // Accent derived from the artist image drives badges, focus rings and the
    // current-track highlights on this screen only; secondary is already
    // contrast-corrected by ensureReadable() so it reads on the dark wash.
    val accent = dyn?.secondary ?: com.hans.aura.ui.theme.Accent
    val atmosAlbumIds = state.atmosAlbumIds
    var showBioDialog by remember { mutableStateOf(false) }

    val contentFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val focusKeeper = remember { com.hans.aura.ui.common.FocusKeeper() }
    var focusedId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    // Breadcrumb for the D-pad walk: proves from logcat which section the cursor
    // reached instead of needing a screenshot.
    var lastFocusSection by remember { mutableStateOf("") }
    fun logFocus(section: String, id: String) {
        if (lastFocusSection == section) return
        lastFocusSection = section
        android.util.Log.d("AURA_ARTIST", "focus=$section id=$id artist=${artist.id}")
    }
    // A PUSH must start at the top: SaveableStateProvider restores this slot's
    // scroll + focused item from the previous visit to the same artist, and the
    // restored focus then scrolled the album row off the top of the page (seen
    // live: the page opened with "Similar artists" at the header edge and no
    // albums). Only a BACK return may restore focus/scroll.
    if (!initialFocus) {
        com.hans.aura.ui.common.RestoreFocusOnBack(focusTick, contentFocus, focusedId, focusKeeper)
    }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(artist.id, initialFocus) {
        if (!initialFocus) return@LaunchedEffect
        kotlinx.coroutines.delay(120)
        runCatching { listState.scrollToItem(0) }
    }
    com.hans.aura.ui.common.InitialFocusOnPush(initialFocus, contentFocus)

    Box(
        Modifier
            .fillMaxSize()
            .background(
                // dyn.primary is ALREADY darkened (PaletteCache applies 0.30
                // when it packs the palette) — darken()ing it again collapsed
                // every wash to near-black. Use it as-is and derive the mid stop.
                Brush.verticalGradient(
                    listOf(
                        dyn?.primary ?: Surface,
                        dyn?.let { darken(it.primary, 0.55f) }
                            ?: com.hans.aura.ui.theme.SurfaceHigh,
                        com.hans.aura.ui.theme.Canvas
                    )
                )
            )
    ) {
        Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 48.dp, vertical = 28.dp)
                .background(
                    Color(0x990E0C0A),
                    androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                )
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                androidx.tv.material3.Text(shownArtist.name, fontSize = 36.sp, color = TextPrimary)
                state.overview?.let { bio ->
                    androidx.tv.material3.Text(
                        bio,
                        fontSize = 13.sp, color = TextPrimary,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .width(900.dp)
                            .clickable { showBioDialog = true }
                    )
                }
            }
            Spacer(Modifier.width(24.dp))
            // Atmos filter (icon-only): hidden while the Settings preference is
            // on, since that already removes every non-Atmos album here.
            if (!com.hans.aura.data.AtmosFilter.on) {
                com.hans.aura.ui.common.AtmosFilterPill(
                    selected = state.atmosView,
                    accent = accent,
                    onClick = { viewModel.toggleAtmos() }
                )
                Spacer(Modifier.width(12.dp))
            }
            // Radio: GET /Artists/{id}/InstantMix, ~50 tracks, queue replaces.
            ArtistRadioButton(
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
            Spacer(Modifier.width(12.dp))
            androidx.tv.material3.Button(onClick = onBack) {
                androidx.tv.material3.Text("Back")
            }
        }

        if (state.error != null && !state.hasContent) {
            ErrorPane(
                message = "Couldn't load from server",
                detail = state.error,
                onRetry = { viewModel.retry() }
            )
            return@Column
        }

        // LazyColumn, not a verticalScroll Column: in the scrolling-column layout
        // the D-pad could not leave the albums row downward at all (focus stayed
        // on the same card while a swipe scrolled the page fine), so "Similar
        // artists" and "Top tracks" were unreachable with a remote. Lazy items
        // give focus search a real, measured target per row.
        androidx.compose.foundation.lazy.LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(contentFocus)
                .focusRestorer()
        ) {
            // The first two items are emitted UNCONDITIONALLY (empty while their
            // query is in flight). Adding a 0-height item later is fine; inserting
            // a new item ABOVE the current one makes LazyColumn keep the old item
            // anchored, which pushed the albums row off the top on entry.
            item(key = "albums") {
                Column {
                    // NO dedupe (user decision 2026-11-15): the FLAC copy in
                    // "Music" and the E-AC-3 JOC copy in "Atmos Content" are two
                    // albums, and hiding one made the Atmos release disappear.
                    // "Only Atmos" is a FILTER (settings switch + icon pill).
                    val visibleAlbums = state.visibleAlbums
                    if (visibleAlbums.isNotEmpty()) {
                        SectionHeader("Albums", accent)
                        Spacer(Modifier.height(12.dp))
                        Row(
                            Modifier
                                .padding(horizontal = 48.dp)
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .focusRestorer(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            for (album in visibleAlbums) {
                                ArtworkCard(
                                    item = album,
                                    serverUrl = session.serverUrl,
                                    size = 180.dp,
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
                        }
                        Spacer(Modifier.height(28.dp))
                    }
                }
            }

            item(key = "similar") {
                Column {
                    val list = state.similar
                    if (!list.isNullOrEmpty()) {
                        com.hans.aura.ui.common.ItemShelf(
                            title = "Similar artists",
                            items = list,
                            serverUrl = session.serverUrl,
                            focusKeeper = focusKeeper,
                            onFocused = {
                                focusedId = it
                                logFocus("similar", it)
                            },
                            onOpen = { item, _ -> onOpenArtist(item) }
                        )
                    }
                }
            }

            val visibleTracks = state.visibleTopTracks
            if (visibleTracks.isNotEmpty()) {
                item(key = "topHeader") {
                    Column {
                        SectionHeader("Top tracks", accent)
                        Spacer(Modifier.height(8.dp))
                    }
                }
                itemsIndexed(visibleTracks, key = { _, t -> "top_${t.id}" }) { i, track ->
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
                item(key = "bottomSpacer") { Spacer(Modifier.height(120.dp)) }
            }
        }
        }

        val overview = state.overview
        if (showBioDialog && overview != null) {
            com.hans.aura.ui.common.BioDialog(
                title = shownArtist.name,
                subtitle = "Artist",
                text = overview,
                onDismiss = { showBioDialog = false }
            )
        }
    }
}

/**
 * Section title with a short accent rule underneath, tinted from the artist
 * image so the page reads as one themed surface.
 */
@Composable
private fun SectionHeader(title: String, accent: Color) {
    Column(Modifier.padding(horizontal = 48.dp)) {
        androidx.tv.material3.Text(title, fontSize = 20.sp, color = TextPrimary)
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .width(56.dp)
                .height(3.dp)
                .background(accent, androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
        )
    }
}

/**
 * "Radio" pill in the artist header. Starts GET /Artists/{id}/InstantMix and
 * replaces the queue with ~50 tracks (Mixes.playMix logs AURA_MIX).
 * Amber fill = action; grey ring = the D-pad cursor is here.
 */
@Composable
private fun ArtistRadioButton(onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
    Box(
        Modifier
            // onFocusChanged BEFORE clickable, or the ring never renders.
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() }
            .clip(shape)
            .background(if (focused) Color(0xFFF0B667) else Color(0xFFE8A44C))
            .border(3.dp, if (focused) Color(0xFFF2EDE6) else Color.Transparent, shape)
            .padding(horizontal = 18.dp, vertical = 10.dp)
    ) {
        androidx.tv.material3.Text(
            "Radio",
            color = Color(0xFF191410),
            fontSize = 15.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
        )
    }
}
