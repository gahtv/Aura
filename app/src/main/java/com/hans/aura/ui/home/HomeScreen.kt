@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.hans.aura.ui.home

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
import com.hans.aura.ui.theme.TextPrimary
import com.hans.aura.ui.theme.TextSecondary

@Composable
fun HomeScreen(
    app: AuraApp,
    onSignOut: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenCollection: (JellyfinItem) -> Unit,
    onOpenArtist: (JellyfinItem) -> Unit,
    onPlayTrack: (track: JellyfinItem, queue: List<JellyfinItem>, index: Int) -> Unit,
    refreshKey: Int = 0,
    focusTick: Int = 0
) {
    val session = app.jellyfin.auth.session!!
    val userId = session.userId
    val serverUrl = session.serverUrl

    var recentlyPlayed by remember(refreshKey) { mutableStateOf(LibraryCache.get("home_recent:$userId")) }
    var recentAlbums by remember(refreshKey) { mutableStateOf(LibraryCache.get("home_new:$userId")) }
    var artists by remember(refreshKey) { mutableStateOf(LibraryCache.get("home_artists:$userId")) }
    var playlists by remember(refreshKey) { mutableStateOf(LibraryCache.get("home_playlists:$userId")) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(refreshKey) {
        if (recentlyPlayed != null && recentAlbums != null && artists != null && playlists != null) {
            return@LaunchedEffect // fully cached
        }
        runCatching {
            val api = app.jellyfin.api
            if (recentlyPlayed == null) {
                api.getItems(
                    userId = userId, includeItemTypes = "Audio", recursive = true,
                    sortBy = "DatePlayed", sortOrder = "Descending", limit = 20
                ).items.also {
                    LibraryCache.put("home_recent:$userId", it)
                    recentlyPlayed = it
                }
            }
            if (recentAlbums == null) {
                api.getItems(
                    userId = userId, includeItemTypes = "MusicAlbum", recursive = true,
                    sortBy = "DateCreated", sortOrder = "Descending", limit = 20
                ).items.also {
                    LibraryCache.put("home_new:$userId", it)
                    recentAlbums = it
                }
            }
            if (artists == null) {
                api.getAlbumArtists(userId = userId, limit = 100).items.also {
                    LibraryCache.put("home_artists:$userId", it)
                    artists = it
                }
            }
            if (playlists == null) {
                api.getItems(
                    userId = userId, includeItemTypes = "Playlist", recursive = true,
                    sortBy = "SortName", limit = 50
                ).items.also {
                    LibraryCache.put("home_playlists:$userId", it)
                    playlists = it
                }
            }
        }.onFailure { error = it.message }
    }

    val contentFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val focusKeeper = remember { com.hans.aura.ui.common.FocusKeeper() }
    var focusedCardId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    com.hans.aura.ui.common.RestoreFocusOnBack(focusTick, contentFocus, focusedCardId, focusKeeper)

    Column(
        Modifier
            .fillMaxSize()
            .padding(vertical = 8.dp)
            .focusRequester(contentFocus)
            .focusRestorer()
    ) {
        if (error != null && recentlyPlayed == null) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                androidx.tv.material3.Text("Library error: $error", color = TextPrimary)
            }
            return@Column
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Shelf("Recently played", recentlyPlayed, serverUrl, focusKeeper, { focusedCardId = it }) { item, idx ->
                // A track is not a collection: play it directly, with the rest
                // of the recently-played list queued behind it.
                onPlayTrack(item, recentlyPlayed ?: listOf(item), idx)
            }
            Shelf("Recently added", recentAlbums, serverUrl, focusKeeper, { focusedCardId = it }) { item, _ ->
                onOpenCollection(item)
            }
            Shelf("Artists", artists, serverUrl, focusKeeper, { focusedCardId = it }) { item, _ ->
                onOpenArtist(item)
            }
            Shelf("Playlists", playlists, serverUrl, focusKeeper, { focusedCardId = it }) { item, _ ->
                onOpenCollection(item)
            }
            Spacer(Modifier.height(140.dp)) // clear of the now-playing bar
        }
    }
}

@Composable
private fun Shelf(
    title: String,
    items: List<JellyfinItem>?,
    serverUrl: String,
    focusKeeper: com.hans.aura.ui.common.FocusKeeper,
    onFocused: (String) -> Unit,
    onOpen: (JellyfinItem, Int) -> Unit
) {
    when {
        items == null -> {
            androidx.tv.material3.Text("$title \u2026", color = TextSecondary, fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 56.dp))
            Spacer(Modifier.height(16.dp))
        }
        items.isEmpty() -> {}
        else -> {
            androidx.tv.material3.Text(title, fontSize = 20.sp, color = TextPrimary,
                modifier = Modifier.padding(horizontal = 56.dp))
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .focusRestorer(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Spacer(Modifier.width(40.dp))
                items.forEachIndexed { idx, item ->
                    ArtworkCard(
                        item = item,
                        serverUrl = serverUrl,
                        size = 180.dp,
                        focusKeeper = focusKeeper,
                        onFocused = onFocused
                    ) {
                        onOpen(item, idx)
                    }
                }
                Spacer(Modifier.width(40.dp))
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
