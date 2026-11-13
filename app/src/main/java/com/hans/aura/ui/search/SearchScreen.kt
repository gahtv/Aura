package com.hans.aura.ui.search

import androidx.compose.foundation.layout.Arrangement
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
import com.hans.aura.ui.common.ArtworkCard
import com.hans.aura.ui.common.TrackRow
import com.hans.aura.ui.player.PlayerConnection
import com.hans.aura.ui.theme.TextPrimary
import com.hans.aura.ui.theme.TextSecondary

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

    Column(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 32.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
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
            modifier = Modifier.fillMaxWidth().focusRequester(searchFieldFocus)
        )
        Spacer(Modifier.height(16.dp))

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            val q = query.trim()
            if (q.length >= 2 && !searching &&
                tracks?.isEmpty() == true && albums?.isEmpty() == true && artists?.isEmpty() == true
            ) {
                Text("No results for \u201C$q\u201D", color = TextSecondary, fontSize = 15.sp)
            }

            SectionHeader("Tracks")
            tracks?.forEachIndexed { i, item ->
                TrackRow(
                    index = i + 1,
                    track = item,
                    isCurrent = item.id == current?.id,
                    onClick = { tracks?.let { player.playQueue(it, i) } }
                )
            }

            SectionHeader("Albums")
            albums?.forEach { item ->
                ArtworkCard(
                    item = item,
                    serverUrl = session.serverUrl,
                    size = 160.dp
                ) { onOpenCollection(item) }
                Spacer(Modifier.height(16.dp))
            }

            SectionHeader("Artists")
            artists?.forEach { item ->
                ArtworkCard(
                    item = item,
                    serverUrl = session.serverUrl,
                    size = 160.dp
                ) { onOpenArtist(item) }
                Spacer(Modifier.height(16.dp))
            }

            Spacer(Modifier.height(120.dp))
        }
    }
}

@Composable
private fun SectionHeader(label: String) {
    Text(
        label,
        fontSize = 20.sp,
        color = TextPrimary,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp)
    )
}
