@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.hans.aura.ui.nowplaying

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import com.hans.aura.ui.common.OverlayVisibility
import com.hans.aura.ui.theme.FocusOutline
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.hans.aura.AuraApp
import com.hans.aura.R
import com.hans.aura.data.AuthStore
import com.hans.aura.data.JellyfinItem
import com.hans.aura.data.Mixes
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
    // Own scope for the heart toggle (a favourite write is fire-and-forget).
    val npScope = rememberCoroutineScope()

    // Tell the mirror link which face of this screen is up, so a remote shows
    // its own lyrics view exactly when the TV does.
    LaunchedEffect(showLyrics) { com.hans.aura.ui.MirrorLink.publishLyrics(showLyrics) }
    DisposableEffect(Unit) {
        onDispose { com.hans.aura.ui.MirrorLink.publishLyrics(false) }
    }
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

    // The auto-push into Now Playing does not always set pendingFocus, so the
    // screen could open with NOTHING focused — no ring anywhere and the D-pad
    // felt dead until the first press — or with focus parked on Back (pressing
    // OK immediately left the screen). Always claim play/pause on entry.
    LaunchedEffect(Unit) {
        delay(250)
        repeat(6) {
            if (runCatching { playPauseFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(100)
        }
    }

    // ---- Queue peek (B14) ---------------------------------------------------
    // The transport row is the bottom of the screen's focus tree, so DOWN from
    // it has nowhere to go. Spend that key on a compact "up next" peek instead:
    // 0 = closed, 3 = peek, 5 = extended peek.
    val queueIndexState by player.queueIndex.collectAsState()
    var peekSize by remember { mutableStateOf(0) }
    var peekFocusToken by remember { mutableStateOf(0) }
    var peekGraceUntil by remember { mutableStateOf(0L) }
    // Everything queued after the playing track, in real play order (the
    // player's order, not the order the user tapped — shuffle included).
    val upcoming = queueState.drop((queueIndexState + 1).coerceAtLeast(0))

    fun closePeek(log: Boolean = true) {
        if (peekSize == 0) return
        peekSize = 0
        if (log) android.util.Log.d("AURA_PEEK", "closed")
        // Focus was trapped inside the overlay; hand it back to play/pause or
        // the screen is left with no ring after BACK.
        npScope.launch {
            delay(120)
            repeat(5) {
                if (runCatching { playPauseFocus.requestFocus() }.isSuccess) return@launch
                delay(80)
            }
        }
    }

    fun openPeek() {
        if (upcoming.isEmpty()) return
        peekSize = 3
        peekFocusToken++
        // The DOWN that opened the peek is still held: swallow its repeats (and
        // any OK release) for a second so the peek cannot skip straight to 5 or
        // click the first row on the same press.
        peekGraceUntil = android.os.SystemClock.uptimeMillis() + 1000L
        android.util.Log.d("AURA_PEEK", "open=3 index=$queueIndexState")
    }

    fun extendPeek() {
        if (peekSize >= 5 || upcoming.size <= 3) return
        peekSize = 5
        android.util.Log.d("AURA_PEEK", "open=5 index=$queueIndexState")
    }

    // The queue can change under an open peek (a track ends, something is
    // removed): an empty list would leave focus with nothing to land on.
    LaunchedEffect(upcoming.isEmpty(), peekSize) {
        if (peekSize > 0 && upcoming.isEmpty()) closePeek()
    }

    // BACK closes the peek instead of leaving the screen. Only one of the two
    // BACK handlers (lyrics / peek) can be enabled at a time.
    androidx.activity.compose.BackHandler(enabled = peekSize > 0) { closePeek() }

    // The lyrics view has no transport row, so a peek cannot survive into it.
    LaunchedEffect(showLyrics) { if (showLyrics && peekSize > 0) closePeek() }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(dynBg, com.hans.aura.ui.theme.Canvas)))
                // Lyrics view is full-bleed edge to edge; the track view keeps the
                // standard screen insets.
                .padding(
                    horizontal = if (showLyrics) 0.dp else 64.dp,
                    vertical = if (showLyrics) 0.dp else 32.dp
                )
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
                Row(
                    Modifier
                        .fillMaxWidth()
                        .then(
                            // Lyrics view has no Column padding; inset the controls row itself.
                            if (showLyrics) Modifier.padding(start = 64.dp, end = 64.dp, top = 32.dp)
                            else Modifier
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    NpControlButton(label = "Back", onClick = onBack)
                    Spacer(Modifier.weight(1f))
                    if (isAtmos(item)) AtmosBadge(color = dynAccent)
                    Spacer(Modifier.weight(1f))
                    NpControlButton(label = "Queue", onClick = onOpenQueue)
                    Spacer(Modifier.width(12.dp))
                    NpControlButton(
                        label = "Add to playlist",
                        enabled = item != null,
                        onClick = { item?.let { ContextMenuHost.openPicker(listOf(it)) } }
                    )
                    Spacer(Modifier.width(12.dp))
                    NpControlButton(
                        label = "Save queue as playlist",
                        enabled = queueState.isNotEmpty(),
                        onClick = { ContextMenuHost.openPicker(queueState) }
                    )
                    Spacer(Modifier.width(12.dp))
                    NpControlButton(
                        label = if (showLyrics) "Track" else "Lyrics",
                        onClick = { showLyrics = !showLyrics }
                    )
                    Spacer(Modifier.width(12.dp))
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
                        // Big title: shrink to fit, then marquee. Available width
                        // comes from the row's weight(1f) column.
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            com.hans.aura.ui.common.AutoFitMarqueeText(
                                text = item?.name ?: "Nothing playing",
                                widthCap = maxWidth,
                                sizes = listOf(40.sp, 34.sp, 28.sp),
                                color = TextPrimary
                            )
                        }
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
                        TransportBar(
                            player, position, duration, isPlaying, playPauseFocus,
                            accent = dynAccent,
                            favorite = item?.let { com.hans.aura.data.Favorites.isFavorite(it) } == true,
                            onToggleFavorite = item?.let { cur ->
                                {
                                    npScope.launch {
                                        com.hans.aura.data.Favorites.toggle(
                                            app.jellyfin.api, session.userId, cur
                                        )
                                    }
                                }
                            },
                            // DOWN off the transport row opens the queue peek
                            // (only while it is closed; once it is up, the
                            // overlay owns the D-pad).
                            modifier = Modifier.onPreviewKeyEvent { e ->
                                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown) {
                                    android.util.Log.d(
                                        "AURA_PEEK",
                                        "down: peek=$peekSize upcoming=${upcoming.size} idx=$queueIndexState"
                                    )
                                }
                                if (e.type == KeyEventType.KeyDown &&
                                    e.key == Key.DirectionDown &&
                                    peekSize == 0
                                ) {
                                    // Nothing queued behind this track: leave
                                    // the key alone rather than eating it.
                                    val willOpen = upcoming.isNotEmpty()
                                    if (willOpen) openPeek()
                                    willOpen
                                } else false
                            },
                            // Track radio: re-seed an Instant Mix from the
                            // CURRENT track through the shared Mixes entry
                            // point (the request itself is not reimplemented).
                            onRadio = item?.let { seed ->
                                {
                                    npScope.launch {
                                        Mixes.playMix(
                                            api = app.jellyfin.api,
                                            userId = session.userId,
                                            player = player,
                                            kind = Mixes.Kind.TRACK,
                                            seed = seed
                                        )
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
        // Queue peek (B14): the LAST child of the screen-root Box, so it draws
        // over the lower part of the screen — including the transport row that
        // opened it — and never in place of the full queue screen, which the
        // Queue button still opens.
        if (peekSize > 0) {
            QueuePeekOverlay(
                upcoming = upcoming.take(peekSize),
                total = upcoming.size,
                accent = dynAccent,
                focusToken = peekFocusToken,
                graceUntil = peekGraceUntil,
                onExtend = { extendPeek() },
                onCollapse = {
                    // 5 -> 3 -> closed, so UP mirrors DOWN instead of throwing
                    // the whole list away from the extended state.
                    if (peekSize > 3) {
                        peekSize = 3
                        android.util.Log.d("AURA_PEEK", "open=3 index=$queueIndexState")
                    } else {
                        closePeek()
                    }
                },
                onJump = { qIndex ->
                    android.util.Log.d("AURA_PEEK", "jump index=$qIndex")
                    closePeek(log = false)
                    player.skipToQueue(qIndex)
                }
            )
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
            // Before the focus target, like every other focusable in the app.
            .onFocusChanged {
                android.util.Log.d("AURA_NAV", "jump focus $tag focused=${it.isFocused}")
                focused = it.isFocused
            }
            .clickable(onClickLabel = tag) { onClick() }
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
            .padding(start = 0.dp, bottom = 0.dp)
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
        Column(Modifier.widthIn(max = 380.dp)) {
            // Same shrink-then-marquee treatment as the full Now Playing title;
            // the cap keeps the chip from swallowing the lyric pane.
            com.hans.aura.ui.common.AutoFitMarqueeText(
                text = item?.name ?: "",
                widthCap = 380.dp,
                sizes = listOf(20.sp, 18.sp, 16.sp),
                color = TextPrimary
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
 * next), then shuffle + repeat + heart + Radio toggles.
 *
 * [modifier] is how the screen hangs the queue-peek DOWN handler on the row;
 * [onRadio] seeds an Instant Mix from the current track (null when nothing is
 * playing, so the control simply is not drawn).
 */
@Composable
private fun TransportBar(
    player: PlayerConnection,
    position: Long,
    duration: Long,
    isPlaying: Boolean,
    playPauseFocus: androidx.compose.ui.focus.FocusRequester,
    accent: Color,
    favorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    onRadio: (() -> Unit)? = null
) {
    val shuffleEnabled by player.shuffleEnabled.collectAsState()
    val repeatMode by player.repeatMode.collectAsState()

    Column(modifier) {
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
            if (onToggleFavorite != null) {
                Spacer(Modifier.width(24.dp))
                // Heart for the CURRENT track: filled accent when liked.
                TransportHeartButton(
                    favorite = favorite,
                    accent = accent,
                    onClick = onToggleFavorite
                )
            }
            onRadio?.let { radio ->
                Spacer(Modifier.width(24.dp))
                // Track radio: same loud focus language as the top-row control
                // buttons, sat in the transport row next to the heart. No
                // `active` state — pressing it starts a new mix, it is not a
                // toggle.
                NpControlButton(label = "Radio", onClick = radio)
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
        focused -> accent.copy(alpha = 0.34f)
        active -> accent.copy(alpha = 0.18f)
        filled -> Color(0x22FFFFFF)
        else -> Color.Transparent
    }
    val tint by animateColorAsState(
        if (active || focused || filled) accent else TextPrimary,
        label = "transportTint"
    )
    // Focus grows the glyph (~15%) as well as ringing it: on a dark 10-foot
    // screen a 3dp accent ring alone was too easy to miss.
    val grow by animateFloatAsState(if (focused) 1.15f else 1f, label = "transportGrow")
    Box(
        modifier
            .size(iconSize + 40.dp)
            .graphicsLayer {
                scaleX = grow
                scaleY = grow
            }
            .clip(CircleShape)
            .background(plate)
            .border(
                width = if (focused) 3.dp else 0.dp,
                color = if (focused) Color(0xFFD6CCBE) else Color.Transparent,
                shape = CircleShape
            )
            // onFocusChanged must sit BEFORE the focus target (clickable):
            // placed after it, it observes the un-focusable layout child and
            // never reports focus, so the ring/scale never rendered.
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClickLabel = label) { onClick() },
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
 * Heart variant of the transport button. Same plate/ring/scale language as
 * TransportIconButton, but the glyph is a text heart because there is no heart
 * vector drawable in res/. `active` (liked) keeps the accent tint at rest.
 */
@Composable
private fun TransportHeartButton(favorite: Boolean, accent: Color, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val plate = when {
        focused -> accent.copy(alpha = 0.34f)
        favorite -> accent.copy(alpha = 0.18f)
        else -> Color.Transparent
    }
    val tint by animateColorAsState(
        if (favorite || focused) accent else TextPrimary,
        label = "heartTint"
    )
    val grow by animateFloatAsState(if (focused) 1.15f else 1f, label = "heartGrow")
    val label = if (favorite) "Remove from favorites" else "Add to favorites"
    Box(
        Modifier
            .size(64.dp)
            .graphicsLayer {
                scaleX = grow
                scaleY = grow
            }
            .clip(CircleShape)
            .background(plate)
            .border(
                width = if (focused) 3.dp else 0.dp,
                color = if (focused) Color(0xFFD6CCBE) else Color.Transparent,
                shape = CircleShape
            )
            // Before the focus target, as everywhere else in the app.
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClickLabel = label) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        androidx.tv.material3.Text(
            if (favorite) "\u2665" else "\u2661",
            color = tint,
            fontSize = 26.sp
        )
    }
}

@Composable
fun AtmosBadge(color: Color = Accent) {
    Box(
        Modifier
            .background(color.copy(alpha = 0.20f), RoundedCornerShape(4.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        androidx.tv.material3.Text(
            "DD+ \u2022 ATMOS",
            color = color, fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * Top-row control on Now Playing. The tv-material Button's focused state is a
 * barely-visible tint on a dark screen, so these draw the app's cursor language
 * explicitly: light grey outline + lifted fill, and the border thickens the
 * moment focus lands.
 */
@Composable
private fun NpControlButton(
    label: String,
    enabled: Boolean = true,
    active: Boolean = false,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    val bg by animateColorAsState(
        when {
            !enabled -> Color(0x14FFFFFF)
            focused -> Color(0xFF3E362D)
            else -> Color(0xFF2A2620)
        },
        label = "npControlBg"
    )
    Box(
        Modifier
            .clip(shape)
            .background(bg)
            .border(
                width = if (focused) 3.dp else 0.dp,
                color = if (focused) Color(0xFFD6CCBE) else Color.Transparent,
                shape = shape
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(enabled = enabled, onClickLabel = label) { onClick() }
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        androidx.tv.material3.Text(
            label,
            color = when {
                !enabled -> TextSecondary
                active -> Color(0xFFE8A44C)
                else -> TextPrimary
            },
            fontSize = 15.sp,
            fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

/**
 * Inline queue peek (B14): the next few tracks as a compact list over the
 * lower part of Now Playing.
 *
 * It is deliberately NOT the queue screen — the Queue button still opens the
 * full list — so it only offers two things: look ahead, and jump.
 *
 * Focus behaviour, which is the whole point on a D-pad-only remote:
 *  - the parent renders this as the LAST child of the screen-root Box, so it
 *    covers the transport row that opened it;
 *  - the first row claims focus ~100ms after composition (with retries,
 *    because a request before first layout silently does nothing);
 *  - focus is TRAPPED (exit = Cancel), so UP/DOWN can never land on an
 *    invisible control behind the panel;
 *  - the key that opened the peek is still held for a moment, so DOWN repeats
 *    and any OK release are swallowed for a grace window ([graceUntil]) —
 *    without that the first row got clicked by the same press.
 */
@Composable
private fun QueuePeekOverlay(
    upcoming: List<JellyfinItem>,
    total: Int,
    accent: Color,
    focusToken: Int,
    graceUntil: Long,
    onExtend: () -> Unit,
    onCollapse: () -> Unit,
    onJump: (Int) -> Unit
) {
    // The app-level now-playing bar must not draw over the peek while it is up.
    OverlayVisibility.dialogOpen = true
    DisposableEffect(Unit) { onDispose { OverlayVisibility.dialogOpen = false } }

    val firstFocus = remember { FocusRequester() }
    // focusToken changes on every OPEN (not on extend), so extending does not
    // yank focus back to the top row.
    LaunchedEffect(focusToken) {
        delay(100)
        repeat(6) {
            if (runCatching { firstFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(100)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { e ->
                val stillHeld = android.os.SystemClock.uptimeMillis() < graceUntil
                when {
                    // The held DOWN (its repeats) and an OK release from the
                    // press that opened the peek must not drive the list.
                    stillHeld && (e.key == Key.DirectionDown ||
                        e.key == Key.DirectionCenter || e.key == Key.Enter) -> true
                    e.type != KeyEventType.KeyDown -> false
                    e.key == Key.DirectionDown -> { onExtend(); true }
                    e.key == Key.DirectionUp -> { onCollapse(); true }
                    else -> false
                }
            }
    ) {
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 64.dp, bottom = 56.dp)
                .width(560.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xF20E0C0A))
                .border(1.dp, Border, RoundedCornerShape(12.dp))
                // Focus can never leave the panel while it is up.
                .focusProperties { exit = { FocusRequester.Cancel } }
                .padding(vertical = 10.dp)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.tv.material3.Text(
                    "Up next",
                    fontSize = 12.sp,
                    color = accent,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.weight(1f))
                androidx.tv.material3.Text(
                    if (upcoming.size > 3) "$total queued \u00B7 DOWN for more"
                    else "$total queued",
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }
            upcoming.forEachIndexed { i, track ->
                PeekRow(
                    track = track,
                    accent = accent,
                    modifier = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier
                ) {
                    onJump(i)
                }
            }
        }
    }
}

/**
 * One compact "up next" line: title, artist, duration. Focus is loud on
 * purpose (filled row + outline + accent bar) — the transport row behind it is
 * still visible and must never look like the focus target.
 */
@Composable
private fun PeekRow(
    track: JellyfinItem,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .height(46.dp)
            .clip(shape)
            .background(if (focused) SurfaceHigh else Color.Transparent)
            .then(if (focused) Modifier.border(2.dp, FocusOutline, shape) else Modifier)
            // Before the focus target, as everywhere else in the app.
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClickLabel = "Jump to ${track.name}") { onClick() }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(22.dp)
                .background(
                    if (focused) accent else Color.Transparent,
                    RoundedCornerShape(2.dp)
                )
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            androidx.tv.material3.Text(
                track.name,
                fontSize = 16.sp,
                color = TextPrimary,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            androidx.tv.material3.Text(
                track.artistNames.ifBlank { "Unknown artist" },
                fontSize = 12.sp,
                color = TextSecondary,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(12.dp))
        androidx.tv.material3.Text(
            formatDuration(track.durationMs),
            fontSize = 12.sp,
            color = TextSecondary
        )
    }
}
