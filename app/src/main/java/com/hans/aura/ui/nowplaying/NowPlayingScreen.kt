package com.hans.aura.ui.nowplaying

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.hans.aura.AuraApp
import com.hans.aura.R
import com.hans.aura.data.AuthStore
import com.hans.aura.data.JellyfinItem
import com.hans.aura.data.LyricLine
import com.hans.aura.data.LyricsStore
import com.hans.aura.ui.common.ContextMenuHost
import com.hans.aura.ui.common.formatDuration
import com.hans.aura.ui.player.PlayerConnection
import com.hans.aura.ui.player.PlayerConnection.Companion.isAtmos
import com.hans.aura.ui.theme.Accent
import com.hans.aura.ui.theme.Border
import com.hans.aura.ui.theme.Surface
import com.hans.aura.ui.theme.SurfaceHigh
import com.hans.aura.ui.theme.TextPrimary
import com.hans.aura.ui.theme.TextSecondary
import com.hans.aura.ui.theme.rememberDynamicColors
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun NowPlayingScreen(
    app: AuraApp,
    player: PlayerConnection,
    onBack: () -> Unit,
    initialFocus: Boolean = false,
    onOpenQueue: () -> Unit,
    onOpenAlbum: (JellyfinItem) -> Unit = {},
    onOpenArtist: (JellyfinItem) -> Unit = {}
) {
    val session = app.jellyfin.auth.session!!
    val item by player.current.collectAsState()
    val isPlaying by player.isPlaying.collectAsState()
    val position by player.positionMs.collectAsState()
    val duration by player.durationMs.collectAsState()
    var showLyrics by remember { mutableStateOf(false) }
    val queueState by player.queue.collectAsState()

    // Jump targets: stub items (id + name + type) are enough — the album and
    // artist screens refetch the full item by id on entry.
    val albumItem = item?.albumId?.let { aid ->
        JellyfinItem(id = aid, name = item!!.album ?: "", type = "MusicAlbum")
    }
    val artistEntry = item?.albumArtists?.firstOrNull() ?: item?.artistItems?.firstOrNull()
    val artistItem = artistEntry?.let { JellyfinItem(id = it.id, name = it.name, type = "MusicArtist") }

    // Dynamic color wash from the playing track's album art: darkened dominant
    // as the background, a distinct lighter swatch as the transport/lyric accent.
    val dynArtUrl = item?.primaryImageTag?.let {
        AuthStore.imageUrl(session.serverUrl, item!!.id, it, maxWidth = 480)
    }
    val dyn = rememberDynamicColors(dynArtUrl)
    val dynBg = dyn?.primary ?: Surface
    val dynAccent = dyn?.secondary ?: Accent

    // BACK on the lyrics view returns to the regular now-playing view; only
    // BACK on the track view continues up the stack (pop via onBack).
    androidx.activity.compose.BackHandler(enabled = showLyrics) { showLyrics = false }

    val playPauseFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    com.hans.aura.ui.common.InitialFocusOnPush(initialFocus, playPauseFocus)

    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(dynBg, com.hans.aura.ui.theme.Canvas)))
            .padding(horizontal = 64.dp, vertical = 32.dp)
    ) {
        // Top controls auto-hide after a pause on the lyrics view; any key press
        // brings them back for another cycle.
        var controlsVisible by remember { mutableStateOf(true) }
        LaunchedEffect(showLyrics, controlsVisible) {
            if (showLyrics && controlsVisible) {
                delay(6000)
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
                    onClick = { item?.let { ContextMenuHost.openPicker(listOf(it)) } }
                ) {
                    androidx.tv.material3.Text("Add to playlist")
                }
                Spacer(Modifier.width(12.dp))
                androidx.tv.material3.Button(
                    enabled = queueState.isNotEmpty(),
                    onClick = { ContextMenuHost.openPicker(queueState) }
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

        if (showLyrics) {
            Box(Modifier.fillMaxSize()) {
                LyricsPane(app, player, highlightColor = dynAccent, onUserInteraction = { controlsVisible = true })
                // Album art + song details, bottom-left, ALWAYS visible —
                // draws over the lyric lines once the controls hide.
                NowPlayingChip(app, player)
            }
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
                    // Album / artist lines open their detail screens.
                    albumItem?.let { ab ->
                        JumpText(tag = "album", text = ab.name.ifBlank { "Album" }, accent = dynAccent) {
                            onOpenAlbum(ab)
                        }
                    }
                    artistItem?.let { ar ->
                        JumpText(tag = "artist", text = ar.name.ifBlank { "Artist" }, accent = dynAccent) {
                            onOpenArtist(ar)
                        }
                    }
                    Spacer(Modifier.height(32.dp))
                    TransportBar(player, position, duration, isPlaying, playPauseFocus, accent = dynAccent)
                }
            }
        }
    }
}

/** Clickable "jump to album/artist" line with a clear focus state. */
@Composable
private fun JumpText(tag: String, text: String, accent: Color, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (focused) accent.copy(alpha = 0.25f) else Color.Transparent)
            .border(
                width = if (focused) 3.dp else 0.dp,
                color = if (focused) accent else Color.Transparent,
                shape = RoundedCornerShape(6.dp)
            )
            .clickable(onClickLabel = tag) { onClick() }
            .onFocusChanged {
                android.util.Log.d("AURA_NAV", "jump focus $tag focused=${it.isFocused}")
                focused = it.isFocused
            }
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        androidx.tv.material3.Text(text, fontSize = 18.sp, color = TextSecondary)
    }
}

/** Persistent bottom-left chip: album art + track title + artist. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.NowPlayingChip(app: AuraApp, player: PlayerConnection) {
    val session = app.jellyfin.auth.session!!
    val item by player.current.collectAsState()
    Row(
        Modifier
            .align(Alignment.BottomStart)
            .padding(start = 0.dp, bottom = 24.dp)
            .background(Color(0xCC0E0C0A), RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        item?.primaryImageTag?.let { tag ->
            AsyncImage(
                model = AuthStore.imageUrl(session.serverUrl, item!!.id, tag, maxWidth = 200),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(SurfaceHigh)
            )
            Spacer(Modifier.width(16.dp))
        }
        Column {
            androidx.tv.material3.Text(
                item?.name ?: "", fontSize = 20.sp, color = TextPrimary,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            androidx.tv.material3.Text(
                item?.artistNames ?: "", fontSize = 14.sp, color = TextSecondary,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Icon-only transport controls under the seek bar, using the dynamic accent.
 * Layout: seek bar, times, transport row (prev / -10s / play-pause / +10s /
 * next), then shuffle + repeat toggles.
 */
@Composable
private fun TransportBar(
    player: PlayerConnection,
    position: Long,
    duration: Long,
    isPlaying: Boolean,
    playPauseFocus: androidx.compose.ui.focus.FocusRequester,
    accent: Color
) {
    val shuffleEnabled by player.shuffleEnabled.collectAsState()
    val repeatMode by player.repeatMode.collectAsState()

    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Border)
        ) {
            val frac = if (duration > 0) position.toFloat() / duration else 0f
            Box(
                Modifier
                    .fillMaxWidth(frac)
                    .height(8.dp)
                    .background(accent)
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            androidx.tv.material3.Text(formatDuration(position), color = TextSecondary, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            androidx.tv.material3.Text(
                "-${formatDuration((duration - position).coerceAtLeast(0))}",
                color = TextSecondary, fontSize = 12.sp
            )
        }
        Spacer(Modifier.height(24.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TransportIconButton(R.drawable.ic_skip_previous, "Previous", accent = accent) {
                player.previous()
            }
            Spacer(Modifier.width(20.dp))
            TransportIconButton(R.drawable.ic_replay_10, "Back 10 seconds", accent = accent) {
                player.seekTo((position - 10_000).coerceAtLeast(0))
            }
            Spacer(Modifier.width(20.dp))
            // Big emphasized play/pause.
            TransportIconButton(
                icon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow,
                label = if (isPlaying) "Pause" else "Play",
                accent = accent,
                iconSize = 40.dp,
                filled = true,
                modifier = Modifier.focusRequester(playPauseFocus)
            ) {
                player.playPause()
            }
            Spacer(Modifier.width(20.dp))
            TransportIconButton(R.drawable.ic_forward_10, "Forward 10 seconds", accent = accent) {
                player.seekTo((position + 10_000).coerceIn(0, duration.coerceAtLeast(1)))
            }
            Spacer(Modifier.width(20.dp))
            TransportIconButton(R.drawable.ic_skip_next, "Next", accent = accent) {
                player.next()
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TransportIconButton(
                icon = R.drawable.ic_shuffle,
                label = "Shuffle",
                accent = accent,
                active = shuffleEnabled
            ) {
                player.setShuffle(!shuffleEnabled)
            }
            Spacer(Modifier.width(24.dp))
            TransportIconButton(
                icon = if (repeatMode == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat,
                label = when (repeatMode) {
                    Player.REPEAT_MODE_ONE -> "Repeat one"
                    Player.REPEAT_MODE_ALL -> "Repeat all"
                    else -> "Repeat off"
                },
                accent = accent,
                active = repeatMode != Player.REPEAT_MODE_OFF
            ) {
                player.setRepeat(
                    when (repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                )
            }
        }
    }
}

/**
 * Circular icon transport button: transparent at rest, visible accent ring +
 * tinted plate when focused, solid plate when active (shuffle/repeat on).
 */
@Composable
private fun TransportIconButton(
    icon: Int,
    label: String,
    accent: Color,
    iconSize: androidx.compose.ui.unit.Dp = 24.dp,
    filled: Boolean = false,
    active: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val plate = when {
        focused -> accent.copy(alpha = 0.30f)
        active -> accent.copy(alpha = 0.18f)
        filled -> Color(0x22FFFFFF)
        else -> Color.Transparent
    }
    val tint by animateColorAsState(
        if (active || focused || filled) accent else TextPrimary,
        label = "transportTint"
    )
    Box(
        modifier
            .size(iconSize + 40.dp)
            .clip(CircleShape)
            .background(plate)
            .border(
                width = if (focused) 3.dp else 0.dp,
                color = if (focused) accent else Color.Transparent,
                shape = CircleShape
            )
            .clickable(onClickLabel = label) { onClick() }
            .onFocusChanged { focused = it.isFocused },
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.material3.Icon(
            painter = painterResource(icon),
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(iconSize)
        )
    }
}

/**
 * Line-synced lyrics, top-anchored: the current line sits near the top with
 * the three previous lines above it, and auto-follows playback. The album
 * art/title chip is drawn by the parent OVER this pane. Manual D-pad scrolling
 * pauses the auto-follow; it resumes after 4s of inactivity, on a seek, or on
 * a track change. Any key press is surfaced to the parent so it can re-show
 * the hidden transport controls.
 */
@Composable
fun LyricsPane(
    app: AuraApp,
    player: PlayerConnection,
    highlightColor: Color = Accent,
    onUserInteraction: () -> Unit = {}
) {
    val item by player.current.collectAsState()
    val position by player.positionMs.collectAsState()
    var lines by remember(item?.id) { mutableStateOf<List<LyricLine>?>(null) }
    // Fresh scroll state per track: without keying on the item id, a track
    // change keeps the previous song's scroll offset until the new song's
    // first line fires a scroll, so the pane lingers at the old end position.
    val listState = remember(item?.id) { LazyListState() }
    val scope = rememberCoroutineScope()
    var followPaused by remember(item?.id) { mutableStateOf(false) }

    LaunchedEffect(item?.id) {
        val id = item?.id ?: return@LaunchedEffect
        lines = LyricsStore.fetch(app.jellyfin.api, id)
    }

    val currentLineIndex = remember(lines, position) {
        computeCurrentLine(lines, position)
    }

    // Anchor offset: the current line lands ~3 line-heights below the top, so
    // exactly the previous three lines stay visible above it.
    // Line box (26/22sp text + 20dp vertical padding) measures ~68dp pitch on
    // TV; 3*70 leaves a small margin so the third previous line never clips.
    val anchorOffsetPx = with(LocalDensity.current) { (3 * 70).dp.roundToPx() }

    fun pauseFollow() {
        followPaused = true
        scope.launch {
            delay(4000)
            followPaused = false
        }
    }

    LaunchedEffect(lines, currentLineIndex, followPaused) {
        if (lines.isNullOrEmpty() || followPaused) return@LaunchedEffect
        // Before the first synced line's timestamp (currentLineIndex == -1)
        // anchor the FIRST line instead.
        val target = if (currentLineIndex >= 0) currentLineIndex else 0
        listState.animateScrollToItem(
            index = target + 1, // +1: head spacer item
            scrollOffset = -anchorOffsetPx
        )
    }

    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                onUserInteraction()
                // DOWN/UP always scroll the lyrics (and pause auto-follow),
                // no matter which child inside the pane holds focus. KeyDown
                // only — the matching KeyUp must not scroll a second step.
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.DirectionDown -> {
                            pauseFollow()
                            scope.launch { listState.scrollBy(160f) }
                            true
                        }
                        Key.DirectionUp -> {
                            pauseFollow()
                            scope.launch { listState.scrollBy(-160f) }
                            true
                        }
                        else -> false
                    }
                } else false
            }
    ) {
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
                modifier = Modifier
                    .fillMaxSize()
                    .focusable(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Small head spacer so the first line never glues to the top
                // edge; the anchor then shows the previous lines above current.
                item { Spacer(Modifier.height(56.dp)) }
                items(lines!!.size) { i ->
                    val line = lines!![i]
                    val isCurrent = i == currentLineIndex
                    val color by animateColorAsState(
                        when {
                            isCurrent -> highlightColor
                            currentLineIndex in 0 until i -> TextSecondary
                            else -> TextPrimary
                        },
                        label = "lyricLine"
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(
                                if (isCurrent) highlightColor.copy(alpha = 0.13f)
                                else Color.Transparent
                            )
                            .clickable(enabled = line.isSynced) {
                                player.seekTo(line.startMs)
                                followPaused = false
                                onUserInteraction()
                            }
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
                // Enough trailing scroll range that the FINAL line can reach the
                // top anchor (the bottom-left chip may overlap it — accepted).
                item { Spacer(Modifier.height(600.dp)) }
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
