package com.hans.aura.ui.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hans.aura.AuraApp
import com.hans.aura.data.JellyfinItem
import com.hans.aura.data.LibraryCache
import com.hans.aura.ui.theme.Border
import com.hans.aura.ui.theme.Surface
import com.hans.aura.ui.theme.TextPrimary
import com.hans.aura.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/**
 * Pick an existing playlist (or type a name for a new one) to add tracks to.
 * Used for "Add current track to playlist" and "Save queue as playlist".
 */
@Composable
fun PlaylistPickerDialog(
    app: AuraApp,
    tracksToAdd: List<JellyfinItem>,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var playlists by remember { mutableStateOf<List<JellyfinItem>?>(null) }
    var creatingName by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        runCatching {
            app.jellyfin.api.getItems(
                userId = app.jellyfin.auth.session!!.userId,
                includeItemTypes = "Playlist", recursive = true,
                sortBy = "SortName", limit = 100
            ).items
        }.onSuccess {
            playlists = it
            LibraryCache.put("home_playlists:${app.jellyfin.auth.session!!.userId}", it)
        }.onFailure { playlists = emptyList() }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .height(720.dp)
            .padding(48.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .widthIn(max = 720.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Surface)
                .padding(32.dp)
        ) {
            androidx.tv.material3.Text(
                if (tracksToAdd.size == 1) "Add to playlist" else "Save ${tracksToAdd.size} tracks as playlist",
                fontSize = 24.sp, color = TextPrimary
            )
            Spacer(Modifier.height(20.dp))

            // Create-new row (inline for "save queue as" flow too)
            androidx.tv.material3.Button(onClick = { creatingName = "" }) {
                androidx.tv.material3.Text("New playlist\u2026")
            }
            Spacer(Modifier.height(16.dp))

            if (creatingName != null) {
                androidx.compose.material3.TextField(
                    value = newName,
                    onValueChange = { newName = it },
                    placeholder = { androidx.compose.material3.Text("Playlist name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    androidx.tv.material3.Button(
                        enabled = newName.isNotBlank(),
                        onClick = {
                            scope.launch {
                                runCatching {
                                    app.jellyfin.api.createPlaylist(
                                        name = newName.trim(),
                                        ids = tracksToAdd.joinToString(",") { it.id },
                                        userId = app.jellyfin.auth.session!!.userId,
                                        body = com.hans.aura.data.PlaylistRequest(
                                            name = newName.trim(),
                                            ids = tracksToAdd.map { it.id },
                                            userId = app.jellyfin.auth.session!!.userId
                                        )
                                    )
                                }.onSuccess {
                                    LibraryCache.evict(
                                        "home_playlists:${app.jellyfin.auth.session!!.userId}"
                                    )
                                    onDismiss()
                                }.onFailure { e -> status = "Failed: ${e.message}" }
                            }
                        }
                    ) { androidx.tv.material3.Text("Create") }
                    androidx.tv.material3.Button(onClick = { creatingName = null }) {
                        androidx.tv.material3.Text("Cancel")
                    }
                }
                Spacer(Modifier.height(20.dp))
            }

            status?.let {
                androidx.tv.material3.Text(it, color = TextSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
            }

            when (playlists) {
                null -> androidx.tv.material3.Text("Loading playlists\u2026", color = TextSecondary)
                else -> {
                    if (playlists!!.isEmpty() && creatingName == null) {
                        androidx.tv.material3.Text(
                            "No playlists yet \u2014 create one above",
                            color = TextSecondary
                        )
                    }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .height(360.dp)
                            .background(Border.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                    ) {
                        playlists!!.forEach { pl ->
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        scope.launch {
                                            runCatching {
                                                app.jellyfin.api.addToPlaylist(
                                                    playlistId = pl.id,
                                                    ids = tracksToAdd.joinToString(",") { it.id }
                                                )
                                            }.onSuccess {
                                                android.util.Log.d(
                                                    "AURA_MENU",
                                                    "picker added ${tracksToAdd.size} track(s) to \"${pl.name}\""
                                                )
                                                LibraryCache.evict("col:${pl.id}")
                                                LibraryCache.evict(
                                                    "home_playlists:${app.jellyfin.auth.session!!.userId}"
                                                )
                                                onDismiss()
                                            }.onFailure { e -> status = "Failed: ${e.message}" }
                                        }
                                    }
                                    .padding(horizontal = 20.dp, vertical = 14.dp)
                            ) {
                                androidx.tv.material3.Text(
                                    pl.name, color = TextPrimary, fontSize = 17.sp
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            androidx.tv.material3.Button(onClick = onDismiss) {
                androidx.tv.material3.Text("Close")
            }
        }
    }
}
