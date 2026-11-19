@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.hans.aura.ui.collection

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.hans.aura.ui.theme.darken
import com.hans.aura.ui.theme.rememberDynamicColors
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
import kotlinx.coroutines.launch
import com.hans.aura.ui.player.PlayerConnection
import com.hans.aura.ui.theme.Surface
import com.hans.aura.ui.theme.AuraColors
import com.hans.aura.ui.theme.AuraShapes
import com.hans.aura.ui.theme.AuraSizes
import com.hans.aura.ui.theme.AuraSpacing
import com.hans.aura.ui.theme.SurfaceHigh
import com.hans.aura.ui.theme.TextPrimary
import com.hans.aura.ui.theme.TextSecondary

/**
 * Album or playlist detail: header with art, then the tracklist.
 * Play All / Shuffle drive the shared queue.
 */
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
    // An external source may store its editorial copy beside the album, not inside
    // the item (so items carry no Overview), so the source is asked first and the item's
    // field stays as the fallback for the Jellyfin flavour. A null (no bio, 404,
    // a playlist, the default source) leaves today's text path untouched.
    var sourceBio by remember(collection.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(collection.id) {
        sourceBio = runCatching { com.hans.aura.data.MusicSources.current?.albumBio(collection.id) }
            .getOrNull()
    }
    val bio = sourceBio?.takeIf { it.isNotBlank() } ?: state.overview
    val currentId by player.current.collectAsState()
    // Motion artwork on this page follows the audio player (pausing music stops
    // the loop rather than leaving a decoder running under a paused app), except
    // before anything has ever played: then the page's own motion is free to run.
    val audioPlaying by player.isPlaying.collectAsState()
    val motionPlaying = audioPlaying || currentId == null
    // Playlist rows sit outside album context, so they carry per-track Atmos
    // chips (album detail pages derive their badge in the header instead).
    val atmosAlbumIds = if (collection.type == "Playlist") state.atmosAlbumIds else emptySet()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // Dynamic color wash from the album art. Kept dark for text contrast;
    // a scrim sits behind the header text so bright artwork stays readable.
    val shown = state.fullItem ?: collection
    val artUrl = shown.primaryImageTag?.let {
        AuthStore.imageUrl(session.serverUrl, shown.id, it, maxWidth = 480)
    }
    // Two-colour palette: primary tints the background wash, secondary becomes
    // the accent for this album page (current-track marker, focus bar, ATMOS
    // badge) so the screen matches its own cover instead of the app amber.
    val dyn = rememberDynamicColors(artUrl)
    val accent = dyn?.secondary ?: com.hans.aura.ui.theme.Accent

    val contentFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val playButtonFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val focusKeeper = remember { com.hans.aura.ui.common.FocusKeeper() }
    var focusedTrackId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    com.hans.aura.ui.common.RestoreFocusOnBack(focusTick, contentFocus, focusedTrackId, focusKeeper)
    com.hans.aura.ui.common.InitialFocusOnPush(initialFocus, playButtonFocus)

    Box(
        Modifier
            .fillMaxSize()
            .background(
                // dyn.primary is already darkened at pack time — darkening it
                // again collapsed the wash to near-black.
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
        // Header with a dark scrim so titles/bios stay readable over bright art.
        Box(
            Modifier
                .fillMaxWidth()
                // 320dp, not 280: label + 40sp title + subtitle + 2-line bio +
                // buttons overflowed 280 and the header Box CLIPPED the buttons
                // (labels rendered half-height). Keep ~40dp of slack below.
                .height(320.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color(0x660E0C0A), Color(0xCC0E0C0A))
                    )
                )
        ) {
            shown.primaryImageTag?.let { tag ->
                // The header stays a still: a square loop blown up to 960x320 crops into an
                // unreadable smear. The motion belongs on the cover itself, below.
                AsyncImage(
                    model = AuthStore.imageUrl(session.serverUrl, shown.id, tag, maxWidth = 720),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            // Heavy uniform scrim: artwork stays visible as texture, but text
            // is always legible no matter how bright the cover is. Neutral black
            // (not the old warm brown 0x9914100C) so the cover's own colour is
            // not muddied on a page that is now themed from that cover.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0x99101010))
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Surface),
                            startY = 200f
                        )
                    )
            )
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(40.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                shown.primaryImageTag?.let { tag ->
                    Box(
                        Modifier
                            .width(160.dp)
                            .height(160.dp)
                            .clip(AuraShapes.card)
                            .background(SurfaceHigh)
                    ) {
                        // The loop plays on the cover tile — card-sized, square, where it reads.
                        MotionArtwork(
                            itemId = shown.id,
                            posterModel = AuthStore.imageUrl(session.serverUrl, shown.id, tag, maxWidth = 400),
                            contentDescription = collection.name,
                            playing = motionPlaying,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                Column(Modifier.padding(start = 28.dp)) {
                    androidx.tv.material3.Text(
                        when (collection.type) {
                            "Playlist" -> "Playlist"
                            else -> collection.year?.toString() ?: "Album"
                        },
                        fontSize = 13.sp, color = TextSecondary
                    )
                    androidx.tv.material3.Text(
                        shown.name, style = MaterialTheme.typography.displayMedium, color = TextPrimary,
                        maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.tv.material3.Text(
                            subtitle(collection, tracks),
                            fontSize = 14.sp, color = TextSecondary
                        )
                        if (tracks?.any { PlayerConnection.isAtmos(it) } == true) {
                            Spacer(Modifier.width(12.dp))
                            AtmosBadge(color = accent)
                        }
                    }
                    bio?.let { text ->
                        Row(
                            Modifier
                                .padding(top = 10.dp)
                                .width(600.dp)
                                .clickable { showBio = true },
                            verticalAlignment = Alignment.Top
                        ) {
                            androidx.tv.material3.Text(
                                text,
                                fontSize = 14.sp, color = TextPrimary,
                                // Exactly two lines: maxLines = 3 left the
                                // ellipsis alone on a third line, which read as
                                // a broken half-sentence and ate button space.
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            // Always-visible affordance: the bio opens a
                            // full-screen reader, which was previously
                            // undiscoverable.
                            androidx.tv.material3.Text(
                                "  More",
                                fontSize = 14.sp, color = com.hans.aura.ui.theme.Accent
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    // Fixed-height buttons: keep them whole no matter how long
                    // the title/bio above grows.
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        androidx.tv.material3.Button(
                            modifier = Modifier.focusRequester(playButtonFocus),
                            colors = androidx.tv.material3.ButtonDefaults.colors(
                                containerColor = accent,
                                contentColor = collectionPlayContentColor(accent),
                                focusedContainerColor = androidx.tv.material3.MaterialTheme.colorScheme.onSurface,
                                focusedContentColor = androidx.tv.material3.MaterialTheme.colorScheme.inverseOnSurface
                            ),
                            onClick = {
                                tracks?.let { player.playQueue(it, 0, resume = false) }
                            }
                        ) { androidx.tv.material3.Text("Play") }
                        androidx.tv.material3.Button(onClick = {
                            tracks?.let { list ->
                                player.playQueue(list.shuffled(), 0, resume = false)
                            }
                        }) { androidx.tv.material3.Text("Shuffle") }
                        // Radio: GET /Items/{albumId}/InstantMix — albums sound
                        // right as a seed; playlists do not, so they are skipped.
                        if (collection.type != "Playlist") {
                            androidx.tv.material3.Button(onClick = {
                                scope.launch {
                                    Mixes.playMix(
                                        api = app.jellyfin.api,
                                        userId = session.userId,
                                        player = player,
                                        kind = Mixes.Kind.ALBUM,
                                        seed = shown
                                    )
                                }
                            }) { androidx.tv.material3.Text("Radio") }
                        }
                        androidx.tv.material3.Button(onClick = onBack) {
                            androidx.tv.material3.Text("Back")
                        }
                    }
                }
            }
        }

        // Tracklist
        // "Only Atmos" (Settings): an album's tracklist drops non-Atmos files.
        // Playlists are never filtered — that would hide tracks the user put there.
        val atmosFiltered = collection.type == "MusicAlbum" && com.hans.aura.data.AtmosFilter.on
        val shownTracks = if (atmosFiltered) {
            tracks?.let { com.hans.aura.data.AtmosFilter.tracks(it) }
        } else tracks
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
                Modifier
                    .fillMaxSize()
                    .focusRequester(contentFocus)
                    .focusRestorer()
            ) {
                val rows = tracklistRows(shownTracks, isAlbum = collection.type == "MusicAlbum")
                itemsIndexed(rows) { rowIndex, row ->
                    if (row is TracklistRow.Disc) {
                        DiscHeader(row.number, first = rowIndex == 0)
                        return@itemsIndexed
                    }
                    val (i, number, track) = row as TracklistRow.Track
                    val removeFromPlaylist: (() -> Unit)? = if (collection.type == "Playlist") {
                        { viewModel.removeFromPlaylist(track.id) }
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
                                    // Playlist rows are reorderable server-side;
                                    // a successful move must evict the 24h
                                    // LibraryCache entry or the page keeps the
                                    // pre-move order.
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
                item { Spacer(Modifier.height(120.dp)) } // room for the now-playing bar
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
    // The catalog's count (childCount) can exceed what was actually indexed: a
    // store-only interlude, or a music video the index deliberately skips
    // (Speak Now: 22 files against a 23-track listing). Say "22 of 23 songs"
    // rather than print a number that contradicts the album's own metadata.
    val expected = collection.childCount ?: 0
    val count = tracks?.size ?: expected
    val kind = if (expected > count) {
        "$count of " + com.hans.aura.ui.common.countLabel(expected, noun)
    } else {
        com.hans.aura.ui.common.countLabel(count, noun)
    }
    val artist = collection.albumArtist?.let { " \u00B7 $it" } ?: ""
    return "$kind$artist"
}
