@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.hans.aura.ui.collection

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.hans.aura.data.AuthStore
import com.hans.aura.ui.theme.darken
import com.hans.aura.ui.theme.rememberDynamicColors
import androidx.compose.ui.unit.sp
import com.hans.aura.AuraApp
import com.hans.aura.data.JellyfinItem
import com.hans.aura.data.LibraryCache
import com.hans.aura.data.LibraryFilter
import com.hans.aura.ui.common.ArtworkCard
import com.hans.aura.ui.common.TrackRow
import com.hans.aura.ui.player.PlayerConnection
import com.hans.aura.ui.theme.Surface
import com.hans.aura.ui.theme.TextPrimary
import com.hans.aura.ui.theme.TextSecondary

/** Artist page: albums grid plus top tracks. */
@Composable
fun ArtistScreen(
    app: AuraApp,
    artist: JellyfinItem,
    player: PlayerConnection,
    onBack: () -> Unit,
    focusTick: Int = 0,
    initialFocus: Boolean = false,
    onOpenCollection: (JellyfinItem) -> Unit
) {
    val session = app.jellyfin.auth.session!!
    var albums by remember { mutableStateOf(LibraryCache.get("artist_albums:${artist.id}")) }
    var topTracks by remember { mutableStateOf(LibraryCache.get("artist_top:${artist.id}")) }
    var overview by remember { mutableStateOf<String?>(null) }
    // The screen is often entered with a STUB artist (id + name only, e.g. from
    // "View artist" in a context menu), which carries no PrimaryImageTag — so
    // the dynamic palette would have no image to read. The getItem call below
    // resolves the real item; keep it and prefer it for art/name.
    var fetchedArtist by remember(artist.id) { mutableStateOf<JellyfinItem?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val current by player.current.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val atmosAlbumIds = com.hans.aura.ui.common.rememberAtmosAlbumIds(app.jellyfin.api, session.userId)

    // Dynamic color wash from the artist image, kept dark for text contrast.
    val shownArtist = fetchedArtist ?: artist
    val artUrl = shownArtist.primaryImageTag?.let {
        AuthStore.imageUrl(session.serverUrl, shownArtist.id, it, maxWidth = 480)
    }
    val dyn = rememberDynamicColors(artUrl)
    // Accent derived from the artist image drives badges, focus rings and the
    // current-track highlights on this screen only; secondary is already
    // contrast-corrected by ensureReadable() so it reads on the dark wash.
    val accent = dyn?.secondary ?: com.hans.aura.ui.theme.Accent
    var showBioDialog by remember { mutableStateOf(false) }

    LaunchedEffect(artist.id) {
        runCatching {
            app.jellyfin.api.getItem(session.userId, artist.id)
        }.onSuccess { full ->
            fetchedArtist = full
            overview = full.overview?.takeIf { o -> o.isNotBlank() }
        }

        if (albums != null && topTracks != null) return@LaunchedEffect
        runCatching {
            val api = app.jellyfin.api
            if (albums == null) {
                LibraryFilter.queryItems(
                    api, session.userId,
                    LibraryFilter.ItemsQuery(
                        includeItemTypes = "MusicAlbum",
                        albumArtistIds = artist.id, recursive = true,
                        sortBy = "ProductionYear", sortOrder = "Descending", limit = 100
                    )
                ).items.also {
                    LibraryCache.put("artist_albums:${artist.id}", it)
                    albums = it
                }
            }
            if (topTracks == null) {
                LibraryFilter.queryItems(
                    api, session.userId,
                    LibraryFilter.ItemsQuery(
                        includeItemTypes = "Audio",
                        albumArtistIds = artist.id, recursive = true,
                        sortBy = "PlayCount", sortOrder = "Descending", limit = 10
                    )
                ).items.also {
                    LibraryCache.put("artist_top:${artist.id}", it)
                    topTracks = it
                }
            }
        }.onFailure { error = it.message }
    }

    val contentFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val focusKeeper = remember { com.hans.aura.ui.common.FocusKeeper() }
    var focusedId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    com.hans.aura.ui.common.RestoreFocusOnBack(focusTick, contentFocus, focusedId, focusKeeper)
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
                overview?.let { bio ->
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
            androidx.tv.material3.Button(onClick = onBack) {
                androidx.tv.material3.Text("Back")
            }
        }

        if (error != null) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                androidx.tv.material3.Text("Library error: $error", color = TextPrimary)
            }
            return@Column
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .focusRequester(contentFocus)
                .focusRestorer()
        ) {
            albums?.let { list ->
                val deduped = list.distinctBy { it.name.lowercase() to (it.year ?: 0) }
                if (deduped.isNotEmpty()) {
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
                        deduped.forEach { album ->
                            ArtworkCard(
                                item = album,
                                serverUrl = session.serverUrl,
                                size = 180.dp,
                                badge = "ATMOS".takeIf { album.id in atmosAlbumIds },
                                badgeColor = accent,
                                accent = accent,
                                focusKeeper = focusKeeper,
                                onFocused = { focusedId = it }
                            ) { onOpenCollection(album) }
                        }
                    }
                    Spacer(Modifier.height(28.dp))
                }
            }

            topTracks?.let { list ->
                val deduped = list.distinctBy { it.name.lowercase() to it.albumId }
                if (deduped.isNotEmpty()) {
                    SectionHeader("Top tracks", accent)
                    Spacer(Modifier.height(8.dp))
                    deduped.forEachIndexed { i, track ->
                        TrackRow(
                            index = i + 1,
                            track = track,
                            isCurrent = track.id == current?.id,
                            atmos = track.albumId != null && track.albumId in atmosAlbumIds,
                            accent = accent,
                            onClick = { player.playQueue(list, i) },
                            focusKeeper = focusKeeper,
                            onFocused = { focusedId = it },
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
                    Spacer(Modifier.height(120.dp))
                }
            }
        }
        }

        if (showBioDialog && overview != null) {
            com.hans.aura.ui.common.BioDialog(
                title = shownArtist.name,
                subtitle = "Artist",
                text = overview!!,
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
