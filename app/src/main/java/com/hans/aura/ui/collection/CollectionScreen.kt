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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.hans.aura.ui.theme.darken
import com.hans.aura.ui.theme.rememberDynamicColors
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.hans.aura.AuraApp
import com.hans.aura.data.AuthStore
import com.hans.aura.data.JellyfinItem
import com.hans.aura.data.LibraryCache
import com.hans.aura.ui.common.TrackRow
import com.hans.aura.ui.nowplaying.AtmosBadge
import kotlinx.coroutines.launch
import com.hans.aura.ui.player.PlayerConnection
import com.hans.aura.ui.theme.Surface
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
    onOpenArtist: (JellyfinItem) -> Unit = {}
) {
    val session = app.jellyfin.auth.session!!
    var tracks by remember { mutableStateOf(LibraryCache.get("col:${collection.id}")) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var overview by remember { mutableStateOf<String?>(null) }
    // Same stub problem as the artist page: coming from a JumpText/context menu
    // gives an id+name item with no ImageTags, so the palette would have nothing
    // to read and the page fell back to the app amber. Keep the fetched item.
    var fullItem by remember(collection.id) { mutableStateOf<JellyfinItem?>(null) }
    var showBio by remember { mutableStateOf(false) }
    val currentId by player.current.collectAsState()
    // Playlist rows sit outside album context, so they carry per-track Atmos
    // chips (album detail pages derive their badge in the header instead).
    val atmosAlbumIds = if (collection.type == "Playlist") {
        com.hans.aura.ui.common.rememberAtmosAlbumIds(app.jellyfin.api, session.userId)
    } else emptySet()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // Dynamic color wash from the album art. Kept dark for text contrast;
    // a scrim sits behind the header text so bright artwork stays readable.
    val shown = fullItem ?: collection
    val artUrl = shown.primaryImageTag?.let {
        AuthStore.imageUrl(session.serverUrl, shown.id, it, maxWidth = 480)
    }
    // Two-colour palette: primary tints the background wash, secondary becomes
    // the accent for this album page (current-track marker, focus bar, ATMOS
    // badge) so the screen matches its own cover instead of the app amber.
    val dyn = rememberDynamicColors(artUrl)
    val accent = dyn?.secondary ?: com.hans.aura.ui.theme.Accent

    LaunchedEffect(collection.id, refresh) {
        runCatching {
            app.jellyfin.api.getItem(session.userId, collection.id)
        }.onSuccess {
            fullItem = it
            overview = it.overview?.takeIf { o -> o.isNotBlank() }
        }

        if (refresh == 0 && tracks != null) return@LaunchedEffect
        runCatching {
            val result = when (collection.type) {
                "Playlist" -> app.jellyfin.api.getPlaylistItems(
                    playlistId = collection.id, userId = session.userId
                )
                else -> app.jellyfin.api.getItems(
                    userId = session.userId, parentId = collection.id,
                    includeItemTypes = "Audio", sortBy = "ParentIndexNumber,IndexNumber,SortName",
                    limit = 500
                )
            }
            LibraryCache.put("col:${collection.id}", result.items)
            tracks = result.items
        }.onFailure { error = it.message }
    }

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
                    AsyncImage(
                        model = AuthStore.imageUrl(session.serverUrl, shown.id, tag, maxWidth = 400),
                        contentDescription = collection.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .width(160.dp)
                            .height(160.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(SurfaceHigh)
                    )
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
                        shown.name, fontSize = 40.sp, color = TextPrimary,
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
                    overview?.let { bio ->
                        Row(
                            Modifier
                                .padding(top = 10.dp)
                                .width(600.dp)
                                .clickable { showBio = true },
                            verticalAlignment = Alignment.Top
                        ) {
                            androidx.tv.material3.Text(
                                bio,
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
                        androidx.tv.material3.Button(onClick = {
                            tracks?.let { player.playQueue(it, 0) }
                        }) { androidx.tv.material3.Text("Play") }
                        androidx.tv.material3.Button(onClick = {
                            tracks?.let { list ->
                                player.playQueue(list.shuffled(), 0)
                            }
                        }) { androidx.tv.material3.Text("Shuffle") }
                        androidx.tv.material3.Button(onClick = onBack) {
                            androidx.tv.material3.Text("Back")
                        }
                    }
                }
            }
        }

        // Tracklist
        when {
            error != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                androidx.tv.material3.Text("Could not load: $error", color = TextPrimary)
            }
            tracks == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                androidx.tv.material3.Text("Loading\u2026", color = TextSecondary)
            }
            tracks!!.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                androidx.tv.material3.Text("Empty", color = TextSecondary)
            }
            else -> LazyColumn(
                Modifier
                    .fillMaxSize()
                    .focusRequester(contentFocus)
                    .focusRestorer()
            ) {
                itemsIndexed(tracks!!) { i, track ->
                    val removeFromPlaylist: (() -> Unit)? = if (collection.type == "Playlist") {
                        {
                            scope.launch {
                                runCatching {
                                    app.jellyfin.api.removeFromPlaylist(collection.id, track.id)
                                }.onSuccess {
                                    LibraryCache.evict("col:${collection.id}")
                                    refresh++
                                }
                            }
                        }
                    } else null
                    TrackRow(
                        index = i + 1,
                        track = track,
                        isCurrent = track.id == currentId?.id,
                        atmos = track.albumId != null && track.albumId in atmosAlbumIds,
                        accent = accent,
                        onClick = { player.playQueue(tracks!!, i) },
                        focusKeeper = focusKeeper,
                        onFocused = { focusedTrackId = it },
                        onRemove = removeFromPlaylist,
                        onLongPress = {
                            com.hans.aura.ui.common.ContextMenuHost.open(
                                com.hans.aura.ui.common.ContextMenus.forTrack(
                                    app, player, scope, track,
                                    onOpenArtist = onOpenArtist,
                                    onOpenCollection = null,
                                    onRemoveFromPlaylist = removeFromPlaylist
                                )
                            )
                        }
                    )
                }
                item { Spacer(Modifier.height(120.dp)) } // room for the now-playing bar
            }
        }
        }

        if (showBio && overview != null) {
            com.hans.aura.ui.common.BioDialog(
                title = collection.name,
                subtitle = collection.albumArtist,
                text = overview!!,
                onDismiss = { showBio = false }
            )
        }
    }
}

private fun subtitle(collection: JellyfinItem, tracks: List<JellyfinItem>?): String {
    val count = tracks?.size ?: 0
    val kind = if (collection.type == "Playlist") "tracks" else "songs"
    val artist = collection.albumArtist?.let { " \u00B7 $it" } ?: ""
    return "$count $kind$artist"
}
