package com.hans.aura.ui.nowplaying

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.hans.aura.AuraApp
import com.hans.aura.data.AuthStore
import com.hans.aura.data.LyricLine
import com.hans.aura.data.LyricsStore
import com.hans.aura.ui.common.formatDuration
import com.hans.aura.ui.player.PlayerConnection
import com.hans.aura.ui.player.PlayerConnection.Companion.isAtmos
import com.hans.aura.ui.theme.Accent
import com.hans.aura.ui.theme.Border
import com.hans.aura.ui.theme.Surface
import com.hans.aura.ui.theme.SurfaceHigh
import com.hans.aura.ui.theme.TextPrimary
import com.hans.aura.ui.theme.TextSecondary
import com.hans.aura.ui.theme.darken
import com.hans.aura.ui.theme.rememberDynamicColor

@Composable
fun NowPlayingScreen(
    app: AuraApp,
    player: PlayerConnection,
    onBack: () -> Unit,
    initialFocus: Boolean = false,
    onOpenQueue: () -> Unit
) {
    val session = app.jellyfin.auth.session!!
    val item by player.current.collectAsState()
    val isPlaying by player.isPlaying.collectAsState()
    val position by player.positionMs.collectAsState()
    val duration by player.durationMs.collectAsState()
    var showLyrics by remember { mutableStateOf(false) }
    var playlistDialogTracks by remember { mutableStateOf<List<com.hans.aura.data.JellyfinItem>?>(null) }
    val queueState by player.queue.collectAsState()

    // Dynamic color wash from the playing track's album art.
    val dynArtUrl = item?.primaryImageTag?.let {
        AuthStore.imageUrl(session.serverUrl, item!!.id, it, maxWidth = 480)
    }
    val dyn = rememberDynamicColor(dynArtUrl)

    val playPauseFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    com.hans.aura.ui.common.InitialFocusOnPush(initialFocus, playPauseFocus)

    Column(
        Modifier
            .fillMaxSize()
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(
                        dyn?.let { darken(it, 0.30f) } ?: Surface,
                        com.hans.aura.ui.theme.Canvas
                    )
                )
            )
            .padding(horizontal = 64.dp, vertical = 32.dp)
    ) {
        // Top controls auto-hide after a pause on the lyrics view; any key press
        // brings them back for another cycle.
        var controlsVisible by remember { mutableStateOf(true) }
        LaunchedEffect(showLyrics, controlsVisible) {
            if (showLyrics && controlsVisible) {
                kotlinx.coroutines.delay(6000)
                controlsVisible = false
            }
        }
        if (!showLyrics) controlsVisible = true

        if (controlsVisible || !showLyrics) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                androidx.tv.material3.Button(onClick = onBack) { androidx.tv.material3.Text("Back") }
                Spacer(Modifier.weight(1f))
                if (isAtmos(item)) AtmosBadge()
                Spacer(Modifier.weight(1f))
                androidx.tv.material3.Button(onClick = onOpenQueue) {
                    androidx.tv.material3.Text("Queue")
                }
                Spacer(Modifier.width(12.dp))
                androidx.tv.material3.Button(
                    enabled = item != null,
                    onClick = { item?.let { playlistDialogTracks = listOf(it) } }
                ) {
                    androidx.tv.material3.Text("Add to playlist")
                }
                Spacer(Modifier.width(12.dp))
                androidx.tv.material3.Button(
                    enabled = queueState.isNotEmpty(),
                    onClick = { playlistDialogTracks = queueState }
                ) {
                    androidx.tv.material3.Text("Save queue as playlist")
                }
                Spacer(Modifier.width(12.dp))
                androidx.tv.material3.Button(onClick = { showLyrics = !showLyrics }) {
                    androidx.tv.material3.Text(if (showLyrics) "Track" else "Lyrics")
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        playlistDialogTracks?.let { tracks ->
            com.hans.aura.ui.playlist.PlaylistPickerDialog(
                app = app,
                tracksToAdd = tracks,
                onDismiss = { playlistDialogTracks = null }
            )
        }

        if (showLyrics) {
            LyricsPane(app, player, onUserInteraction = { controlsVisible = true })
        } else {
            Row(Modifier.fillMaxSize()) {
                item?.primaryImageTag?.let { tag ->
                    AsyncImage(
                        model = AuthStore.imageUrl(session.serverUrl, item!!.id, tag, maxWidth = 800),
                        contentDescription = item?.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .width(320.dp)
                            .height(320.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(SurfaceHigh)
                    )
                }
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = 48.dp, top = 40.dp)
                ) {
                    androidx.tv.material3.Text(
                        item?.name ?: "Nothing playing",
                        fontSize = 40.sp,
                        color = TextPrimary
                    )
                    androidx.tv.material3.Text(
                        listOfNotNull(item?.album, item?.artistNames?.takeIf { it.isNotBlank() })
                            .joinToString(" \u00B7 "),
                        fontSize = 18.sp, color = TextSecondary
                    )
                    Spacer(Modifier.height(48.dp))
                    TransportBar(player, position, duration, isPlaying, playPauseFocus)
                }
            }
        }
    }
}

@Composable
private fun TransportBar(player: PlayerConnection, position: Long, duration: Long, isPlaying: Boolean, playPauseFocus: androidx.compose.ui.focus.FocusRequester) {
    Column {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.tv.material3.Button(onClick = { player.previous() }) {
                androidx.tv.material3.Text("\u23EE")
            }
            androidx.tv.material3.Button(onClick = {
                player.seekTo((position - 10_000).coerceAtLeast(0))
            }) { androidx.tv.material3.Text("\u221210s") }
            androidx.tv.material3.Button(
                onClick = { player.playPause() },
                modifier = Modifier.focusRequester(playPauseFocus)
            ) {
                androidx.tv.material3.Text(if (isPlaying) "\u23F8" else "\u25B6")
            }
            androidx.tv.material3.Button(onClick = {
                player.seekTo((position + 10_000).coerceIn(0, duration.coerceAtLeast(1)))
            }) { androidx.tv.material3.Text("+10s") }
            androidx.tv.material3.Button(onClick = { player.next() }) {
                androidx.tv.material3.Text("\u23ED")
            }
        }
        Spacer(Modifier.height(20.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Border)
        ) {
            val frac = if (duration > 0) position.toFloat() / duration else 0f
            Box(
                Modifier
                    .fillMaxWidth(frac)
                    .height(6.dp)
                    .background(Accent)
            )
        }
        Row(Modifier.fillMaxWidth()) {
            androidx.tv.material3.Text(formatDuration(position), color = TextSecondary, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            androidx.tv.material3.Text(
                "-${formatDuration((duration - position).coerceAtLeast(0))}",
                color = TextSecondary, fontSize = 12.sp
            )
        }
    }
}

/**
 * Line-synced lyrics: the current line is highlighted and auto-follows playback;
 * OK (enter) on any line seeks there. Falls back to plain unsynced text.
 * Shows the album art and track info above; any key press is surfaced to the
 * parent so it can re-show the hidden transport controls.
 */
@Composable
fun LyricsPane(
    app: AuraApp,
    player: PlayerConnection,
    onUserInteraction: () -> Unit = {}
) {
    val session = app.jellyfin.auth.session!!
    val item by player.current.collectAsState()
    val position by player.positionMs.collectAsState()
    var lines by remember(item?.id) { mutableStateOf<List<LyricLine>?>(null) }
    // Fresh scroll state per track: without keying on the item id, a track
    // change keeps the previous song's scroll offset until the new song's
    // first line fires a scroll, so the pane lingers at the old end position.
    val listState = remember(item?.id) { LazyListState() }

    LaunchedEffect(item?.id) {
        val id = item?.id ?: return@LaunchedEffect
        lines = LyricsStore.fetch(app.jellyfin.api, id)
    }

    val currentLineIndex = remember(lines, position) {
        computeCurrentLine(lines, position)
    }

    LaunchedEffect(lines, currentLineIndex) {
        if (currentLineIndex >= 0) {
            // Place the current line's top at the vertical middle of the list
            // (minus half a line height) so it sits centered, never off-screen.
            // In Compose, negative scrollOffset scrolls the item DOWN from the
            // viewport top; positive would push it off the top edge.
            val viewport = listState.layoutInfo.viewportSize.height
            val centerOffset = if (viewport > 0) -(viewport / 2 - 80) else -500
            listState.animateScrollToItem(
                index = currentLineIndex,
                scrollOffset = centerOffset
            )
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { onUserInteraction(); false }
    ) {
        Column(Modifier.fillMaxSize()) {
            // Track header: cover + title + artist
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                item?.primaryImageTag?.let { tag ->
                    AsyncImage(
                        model = AuthStore.imageUrl(session.serverUrl, item!!.id, tag, maxWidth = 200),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .width(96.dp)
                            .height(96.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(SurfaceHigh)
                    )
                }
                Column(Modifier.padding(start = 20.dp)) {
                    androidx.tv.material3.Text(
                        item?.name ?: "", fontSize = 24.sp, color = TextPrimary
                    )
                    androidx.tv.material3.Text(
                        item?.artistNames ?: "", fontSize = 15.sp, color = TextSecondary
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            when {
                lines == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    androidx.tv.material3.Text(
                        "Loading lyrics\u2026", color = TextSecondary, fontSize = 16.sp
                    )
                }
                lines!!.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    androidx.tv.material3.Text(
                        "No lyrics available for this track",
                        color = TextSecondary, fontSize = 16.sp
                    )
                }
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Big spacers center the active line against the screen.
                    item { Spacer(Modifier.height(320.dp)) }
                    items(lines!!.size) { i ->
                        val line = lines!![i]
                        val isCurrent = i == currentLineIndex
                        val color by animateColorAsState(
                            when {
                                isCurrent -> Accent
                                currentLineIndex in 0 until i -> TextSecondary
                                else -> TextPrimary
                            },
                            label = "lyricLine"
                        )
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .background(if (isCurrent) Color(0x22E8A44C) else Color.Transparent)
                                .clickable(enabled = line.isSynced) { player.seekTo(line.startMs) }
                                .padding(vertical = 10.dp)
                        ) {
                            androidx.tv.material3.Text(
                                line.text.ifBlank { "\u00A0" },
                                color = color,
                                fontSize = if (isCurrent) 26.sp else 22.sp,
                                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    item { Spacer(Modifier.height(320.dp)) }
                }
            }
        }
    }
}

fun computeCurrentLine(lines: List<LyricLine>?, positionMs: Long): Int {
    if (lines.isNullOrEmpty()) return -1
    if (!lines[0].isSynced) return 0
    var idx = -1
    for (i in lines.indices) {
        if (positionMs >= lines[i].startMs) idx = i else break
    }
    return idx
}

@Composable
fun AtmosBadge() {
    Box(
        Modifier
            .background(Color(0x33E8A44C), RoundedCornerShape(4.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        androidx.tv.material3.Text(
            "DD+ \u2022 ATMOS",
            color = Accent, fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
