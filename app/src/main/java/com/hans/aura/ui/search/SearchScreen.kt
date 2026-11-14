package com.hans.aura.ui.search

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.hans.aura.AuraApp
import com.hans.aura.data.JellyfinItem
import com.hans.aura.ui.common.FocusKeeper
import com.hans.aura.ui.common.ItemShelf
import com.hans.aura.ui.common.rememberAtmosAlbumIds
import com.hans.aura.ui.player.PlayerConnection
import com.hans.aura.ui.theme.TextPrimary
import com.hans.aura.ui.theme.TextSecondary

// Search results render as home-style horizontal shelves (Artists / Albums /
// Tracks) stacked on one scrollable page — no tabs, all sections at once.
// The header and text field keep a 56dp inset; the shelf rows themselves
// bleed edge-to-edge (their inset lives inside the scroll row).
@Composable
fun SearchScreen(
    app: AuraApp,
    player: PlayerConnection,
    onBack: () -> Unit,
    onOpenCollection: (JellyfinItem) -> Unit,
    onOpenArtist: (JellyfinItem) -> Unit
) {
    val session = app.jellyfin.auth.session!!
    var query by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var tracks by remember { mutableStateOf<List<JellyfinItem>?>(null) }
    var albums by remember { mutableStateOf<List<JellyfinItem>?>(null) }
    var artists by remember { mutableStateOf<List<JellyfinItem>?>(null) }
    var searching by remember { mutableStateOf(false) }
    val current by player.current.collectAsState()
    val atmosAlbumIds = rememberAtmosAlbumIds(app.jellyfin.api, session.userId)
    val focusKeeper = remember { FocusKeeper() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) { tracks = emptyList(); albums = emptyList(); artists = emptyList(); return@LaunchedEffect }
        searching = true
        runCatching {
            val api = app.jellyfin.api
            val result = api.getItems(
                userId = session.userId, searchTerm = q, recursive = true,
                includeItemTypes = "Audio,MusicAlbum,MusicArtist,Playlist",
                limit = 50
            )
            tracks = result.items.filter { it.type == "Audio" }
            albums = result.items.filter { it.type == "MusicAlbum" || it.type == "Playlist" }
            artists = result.items.filter { it.type == "MusicArtist" }
        }.onFailure {
            tracks = emptyList(); albums = emptyList(); artists = emptyList()
        }
        searching = false
    }

    Column(Modifier.fillMaxSize().padding(vertical = 32.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 56.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Search", fontSize = 32.sp, color = TextPrimary)
            Spacer(Modifier.weight(1f))
            Button(onClick = onBack) { Text("Back") }
        }
        Spacer(Modifier.height(16.dp))

        val searchFieldFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            var tries = 0
            while (tries < 10) {
                try { searchFieldFocus.requestFocus(); break } catch (_: Exception) { }
                kotlinx.coroutines.delay(100)
                tries++
            }
        }
        androidx.compose.material3.TextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { androidx.compose.material3.Text("Artists, albums, tracks, playlists") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 56.dp)
                .focusRequester(searchFieldFocus)
        )
        Spacer(Modifier.height(8.dp))

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            val q = query.trim()
            if (q.length >= 2 && !searching &&
                tracks?.isEmpty() == true && albums?.isEmpty() == true && artists?.isEmpty() == true
            ) {
                Text(
                    "No results for \u201C$q\u201D", color = TextSecondary, fontSize = 15.sp,
                    modifier = Modifier.padding(horizontal = 56.dp, vertical = 8.dp)
                )
            }

            ItemShelf(
                title = "Artists",
                items = artists,
                serverUrl = session.serverUrl,
                focusKeeper = focusKeeper,
                onFocused = { },
                onOpen = { item, _ -> onOpenArtist(item) }
            )

            ItemShelf(
                title = "Albums & playlists",
                items = albums,
                serverUrl = session.serverUrl,
                focusKeeper = focusKeeper,
                onFocused = { },
                badgeFor = { if (it.type == "MusicAlbum" && it.id in atmosAlbumIds) "ATMOS" else null },
                onLongPress = { item ->
                    if (item.type == "MusicAlbum" || item.type == "Playlist") {
                        com.hans.aura.ui.common.ContextMenuHost.open(
                            com.hans.aura.ui.common.ContextMenus.forAlbum(
                                app, player, scope, item, onOpenArtist = onOpenArtist
                            )
                        )
                    }
                },
                onOpen = { item, _ -> onOpenCollection(item) }
            )

            ItemShelf(
                title = "Tracks",
                items = tracks,
                serverUrl = session.serverUrl,
                focusKeeper = focusKeeper,
                onFocused = { },
                showLoading = false,
                badgeFor = { track ->
                    if (track.albumId != null && track.albumId in atmosAlbumIds) "ATMOS" else null
                },
                onLongPress = { item ->
                    com.hans.aura.ui.common.ContextMenuHost.open(
                        com.hans.aura.ui.common.ContextMenus.forTrack(
                            app, player, scope, item,
                            onOpenArtist = onOpenArtist, onOpenCollection = onOpenCollection
                        )
                    )
                },
                onOpen = { _, idx ->
                    // Play the clicked track with the rest of the track results queued behind it.
                    tracks?.let { list -> player.playQueue(list, idx.coerceIn(0, list.lastIndex)) }
                }
            )

            Spacer(Modifier.height(120.dp)) // clear of the now-playing bar
        }
    }
}
