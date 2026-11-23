@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.hans.aura.ui.collection

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.tv.material3.MaterialTheme
import coil.compose.AsyncImage
import com.hans.aura.AuraApp
import com.hans.aura.data.AuthStore
import com.hans.aura.data.JellyfinItem
import com.hans.aura.data.Mixes
import com.hans.aura.ui.common.ErrorPane
import com.hans.aura.ui.common.MotionArtwork
import com.hans.aura.ui.common.TrackRow
import com.hans.aura.ui.nowplaying.AtmosBadge
import com.hans.aura.ui.player.PlayerConnection
import com.hans.aura.ui.theme.AuraColors
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

/**
 * Album or playlist detail: 2-column split layout.
 * Left pane anchors cover art, metadata, bio, and Play/Shuffle/Radio/Back;
 * right pane hosts the scrollable tracklist.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CollectionScreen(
    app: AuraApp,
    collection: JellyfinItem,
    player: PlayerConnection,
    onBack: () -> Unit,
    focusTick: Int = 0,
    initialFocus: Boolean = false,
    onOpenArtist: (JellyfinItem) -> Unit = {},
    viewModel: CollectionViewModel = viewModel(
        key = "collection:${collection.id}:${app.jellyfin.auth.session?.userId ?: "anon"}",
        factory = viewModelFactory {
            initializer { CollectionViewModel(app, collection.id, collection.type) }
        }
    )
) {
    val session = app.jellyfin.auth.session ?: return
    val state by viewModel.state.collectAsState()
    val tracks = state.tracks
    var showBio by remember { mutableStateOf(false) }
    var sourceBio by remember(collection.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(collection.id) {
        sourceBio = runCatching { com.hans.aura.data.MusicSources.current?.albumBio(collection.id) }
            .getOrNull()
    }
    val bio = sourceBio?.takeIf { it.isNotBlank() } ?: state.overview
    val currentId by player.current.collectAsState()
    val audioPlaying by player.isPlaying.collectAsState()
    val motionPlaying = audioPlaying || currentId == null
    val atmosAlbumIds = if (collection.type == "Playlist") state.atmosAlbumIds else emptySet()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val shown = state.fullItem ?: collection
    val artUrl = shown.primaryImageTag?.let {
        AuthStore.imageUrl(session.serverUrl, shown.id, it, maxWidth = 480)
    }
    val dyn = rememberDynamicColors(artUrl)
    val accent = dyn?.secondary ?: com.hans.aura.ui.theme.Accent

    val contentFocus = remember { FocusRequester() }
    val playButtonFocus = remember { FocusRequester() }
    val focusKeeper = remember { com.hans.aura.ui.common.FocusKeeper() }
    var focusedTrackId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val atmosFiltered = collection.type == "MusicAlbum" && com.hans.aura.data.AtmosFilter.on
    val shownTracks = if (atmosFiltered) tracks?.let { com.hans.aura.data.AtmosFilter.tracks(it) } else tracks
    com.hans.aura.ui.common.RestoreFocusOnBack(focusTick, contentFocus, focusedTrackId, focusKeeper,
        ready = !shownTracks.isNullOrEmpty(),
        cancelled = shownTracks.isNullOrEmpty() && (shownTracks != null || state.error != null))
    com.hans.aura.ui.common.InitialFocusOnPush(initialFocus, playButtonFocus)

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
        // Subtle atmospheric backdrop behind the split view.
        shown.primaryImageTag?.let { tag ->
            AsyncImage(
                model = AuthStore.imageUrl(session.serverUrl, shown.id, tag, maxWidth = 720),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = 0.16f,
                modifier = Modifier.fillMaxSize()
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0.0f to (dyn?.primary ?: Surface).copy(alpha = 0.78f),
                        0.45f to Canvas.copy(alpha = 0.85f),
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
            // Left pane (~36%): cover, metadata, bio, and action buttons (no fixed height clipping).
            Column(
                modifier = Modifier
                    .weight(0.36f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.Top
            ) {
                shown.primaryImageTag?.let { tag ->
                    Box(
                        Modifier
                            .size(216.dp)
                            .clip(AuraShapes.cardHero)
                            .background(SurfaceHigh)
                    ) {
                        MotionArtwork(
                            itemId = shown.id,
                            posterModel = AuthStore.imageUrl(session.serverUrl, shown.id, tag, maxWidth = 480),
                            contentDescription = collection.name,
                            playing = motionPlaying,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Spacer(Modifier.height(AuraSpacing.md))
                }
                androidx.tv.material3.Text(
                    when (collection.type) {
                        "Playlist" -> "Playlist"
                        else -> collection.year?.toString() ?: "Album"
                    },
                    fontSize = 13.sp,
                    color = accent
                )
                Spacer(Modifier.height(AuraSpacing.xxs))
                androidx.tv.material3.Text(
                    shown.name,
                    style = MaterialTheme.typography.headlineLarge,
                    color = TextPrimary,
                    maxLines = 3,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(AuraSpacing.xs))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.tv.material3.Text(
                        subtitle(collection, tracks),
                        fontSize = 14.sp,
                        color = TextSecondary,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (tracks?.any { PlayerConnection.isAtmos(it) } == true) {
                        Spacer(Modifier.width(AuraSpacing.sm))
                        AtmosBadge(color = accent)
                    }
                }
                bio?.let { text ->
                    Row(
                        Modifier
                            .padding(top = AuraSpacing.sm)
                            .fillMaxWidth()
                            .clickable { showBio = true },
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
                    androidx.tv.material3.Button(
                        modifier = Modifier
                            .focusRequester(playButtonFocus)
                            .focusProperties { right = contentFocus },
                        colors = androidx.tv.material3.ButtonDefaults.colors(
                            containerColor = accent,
                            contentColor = collectionPlayContentColor(accent),
                            focusedContainerColor = MaterialTheme.colorScheme.onSurface,
                            focusedContentColor = MaterialTheme.colorScheme.inverseOnSurface
                        ),
                        onClick = { tracks?.let { player.playQueue(it, 0, resume = false) } }
                    ) { androidx.tv.material3.Text("Play") }
                    androidx.tv.material3.Button(
                        modifier = Modifier.focusProperties { right = contentFocus },
                        onClick = {
                            tracks?.let { list ->
                                player.playQueue(list.shuffled(), 0, resume = false)
                            }
                        }
                    ) { androidx.tv.material3.Text("Shuffle") }
                    if (collection.type != "Playlist") {
                        androidx.tv.material3.Button(
                            modifier = Modifier.focusProperties { right = contentFocus },
                            onClick = {
                                scope.launch {
                                    Mixes.playMix(
                                        api = app.jellyfin.api,
                                        userId = session.userId,
                                        player = player,
                                        kind = Mixes.Kind.ALBUM,
                                        seed = shown
                                    )
                                }
                            }
                        ) { androidx.tv.material3.Text("Radio") }
                    }
                    androidx.tv.material3.Button(
                        modifier = Modifier.focusProperties { right = contentFocus },
                        onClick = onBack
                    ) { androidx.tv.material3.Text("Back") }
                }
            }

            // Right pane (~64%): full-height scrollable tracklist.
            Column(
                modifier = Modifier
                    .weight(0.64f)
                    .fillMaxHeight()
            ) {
                if (state.error != null && tracks != null) {
                    androidx.tv.material3.Text(
                        state.error.orEmpty(),
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = AuraSpacing.xs)
                    )
                }

                when {
                    state.error != null && shownTracks == null -> ErrorPane(
                        message = "Couldn't load from server",
                        detail = state.error,
                        onRetry = { viewModel.retry() }
                    )
                    shownTracks == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        androidx.tv.material3.Text("Loading\u2026", color = TextSecondary)
                    }
                    shownTracks.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        androidx.tv.material3.Text(
                            if (atmosFiltered) "No Atmos tracks in this album" else "Empty",
                            color = TextSecondary
                        )
                    }
                    else -> LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .focusRequester(contentFocus)
                            .focusRestorer()
                            .focusProperties { left = playButtonFocus },
                        contentPadding = PaddingValues(bottom = AuraSizes.bottomBarClearance)
                    ) {
                        val multiDisc = collection.type == "MusicAlbum" && tracks.orEmpty().spansSeveralDiscs()
                        val rows = tracklistRows(shownTracks, multiDisc)
                        itemsIndexed(
                            items = rows,
                            key = { idx, row ->
                                when (row) {
                                    is TracklistRow.Disc -> "disc_${row.number}"
                                    is TracklistRow.Track -> "track_${row.track.id}_$idx"
                                }
                            },
                            contentType = { _, row ->
                                if (row is TracklistRow.Disc) "disc" else "track"
                            }
                        ) { rowIndex, row ->
                            if (row is TracklistRow.Disc) {
                                DiscHeader(row.number, first = rowIndex == 0)
                                return@itemsIndexed
                            }
                            val (i, number, track) = row as TracklistRow.Track
                            val removeFromPlaylist: (() -> Unit)? = if (collection.type == "Playlist") {
                                { viewModel.removeFromPlaylist(track.id, shownTracks.take(i).count { it.id == track.id }) }
                            } else null
                            TrackRow(
                                index = number,
                                track = track,
                                isCurrent = track.id == currentId?.id,
                                atmos = track.albumId?.let { it in atmosAlbumIds } == true,
                                accent = accent,
                                onClick = { player.playQueue(shownTracks, i, resume = true) },
                                focusKeeper = focusKeeper,
                                onFocused = { focusedTrackId = it },
                                onRemove = removeFromPlaylist,
                                onLongPress = {
                                    com.hans.aura.ui.common.ContextMenuHost.open(
                                        com.hans.aura.ui.common.ContextMenus.forTrack(
                                            app, player, scope, track,
                                            onOpenArtist = onOpenArtist,
                                            onOpenCollection = null,
                                            onRemoveFromPlaylist = removeFromPlaylist,
                                            playlistMove = if (collection.type == "Playlist") {
                                                com.hans.aura.ui.common.ContextMenus.PlaylistMove(
                                                    playlistId = collection.id,
                                                    itemId = track.id,
                                                    index = i,
                                                    size = shownTracks.size,
                                                    onMoved = { viewModel.refresh() }
                                                )
                                            } else null
                                        )
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }

        val shownBio = bio
        if (showBio && shownBio != null) {
            com.hans.aura.ui.common.BioDialog(
                title = collection.name,
                subtitle = collection.albumArtist,
                text = shownBio,
                onDismiss = { showBio = false }
            )
        }
    }
}

/** "Disc N" label with a hairline rule, inset to line up with the track numbers. */
@Composable
private fun DiscHeader(number: Int, first: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(
                start = AuraSpacing.lg,
                end = AuraSpacing.lg,
                top = if (first) AuraSpacing.xs else AuraSpacing.lg,
                bottom = AuraSpacing.xs
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.tv.material3.Text(
            "Disc $number",
            style = MaterialTheme.typography.titleSmall,
            color = TextSecondary
        )
        Spacer(Modifier.width(AuraSpacing.sm))
        Box(
            Modifier
                .weight(1f)
                .height(AuraSizes.hairline)
                .background(TextSecondary.copy(alpha = 0.24f))
        )
    }
}

internal fun collectionPlayContentColor(accent: Color): Color {
    val luminance = accent.luminance()
    val inkContrast = (luminance + 0.05f) / (AuraColors.OnAccent.luminance() + 0.05f)
    return when {
        inkContrast >= 4.5f -> AuraColors.OnAccent
        luminance < 0.179f -> Color.White
        else -> Color.Black
    }
}

internal fun subtitle(collection: JellyfinItem, tracks: List<JellyfinItem>?): String {
    val noun = if (collection.type == "Playlist") "track" else "song"
    val expected = collection.childCount ?: 0
    val count = tracks?.size ?: expected
    val kind = if (collection.type != "Playlist" && expected > count) {
        "$count of " + com.hans.aura.ui.common.countLabel(expected, noun)
    } else {
        com.hans.aura.ui.common.countLabel(count, noun)
    }
    val artist = collection.albumArtist?.let { " \u00B7 $it" } ?: ""
    return "$kind$artist"
}
