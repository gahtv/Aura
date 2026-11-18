@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.hans.aura.ui.nowplaying

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.hans.aura.AuraApp
import com.hans.aura.data.AuthStore
import com.hans.aura.data.Favorites
import com.hans.aura.data.JellyfinItem
import com.hans.aura.data.Mixes
import com.hans.aura.ui.MirrorLink
import com.hans.aura.ui.common.AutoFitMarqueeText
import com.hans.aura.ui.common.InitialFocusOnPush
import com.hans.aura.ui.common.MotionArtwork
import com.hans.aura.ui.player.PlayerConnection
import com.hans.aura.ui.theme.Accent
import com.hans.aura.ui.theme.AuraShapes
import com.hans.aura.ui.theme.AuraSizes
import com.hans.aura.ui.theme.AuraSpacing
import com.hans.aura.ui.theme.AuraTypography
import com.hans.aura.ui.theme.Canvas
import com.hans.aura.ui.theme.Surface
import com.hans.aura.ui.theme.SurfaceHigh
import com.hans.aura.ui.theme.TextPrimary
import com.hans.aura.ui.theme.TextSecondary
import com.hans.aura.ui.theme.rememberDynamicColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Now Playing route: state, the dynamic colour wash, focus entry and the
 * track-vs-lyrics branch. The chrome itself lives in
 * [NowPlayingTopRow] / [TransportBar] / [NowPlayingChip] / [QueuePeekOverlay].
 */
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
    val session = app.jellyfin.auth.session ?: return
    val item by player.current.collectAsState()
    val isPlaying by player.isPlaying.collectAsState()
    val position by player.positionMs.collectAsState()
    val duration by player.durationMs.collectAsState()
    var showLyrics by remember { mutableStateOf(false) }
    // Own scope for the heart toggle (a favourite write is fire-and-forget).
    val npScope = rememberCoroutineScope()

    // Tell the mirror link which face of this screen is up, so a remote shows
    // its own lyrics view exactly when the TV does.
    LaunchedEffect(showLyrics) { MirrorLink.publishLyrics(showLyrics) }
    DisposableEffect(Unit) {
        onDispose { MirrorLink.publishLyrics(false) }
    }
    val queueState by player.queue.collectAsState()

    // One stable local for the current track so nothing below has to force-unwrap.
    val currentItem = item

    // Jump targets: stub items (id + name + type) are enough — the album and
    // artist screens refetch the full item by id on entry.
    val albumItem = currentItem?.let { cur ->
        cur.albumId?.let { aid ->
            JellyfinItem(id = aid, name = cur.album ?: "", type = "MusicAlbum")
        }
    }
    val artistEntry = currentItem?.albumArtists?.firstOrNull()
        ?: currentItem?.artistItems?.firstOrNull()
    val artistItem = artistEntry?.let {
        JellyfinItem(id = it.id, name = it.name, type = "MusicArtist")
    }

    // Dynamic color wash from the playing track's album art: darkened dominant
    // as the background, a distinct lighter swatch as the transport/lyric accent.
    val dynArtUrl = currentItem?.let { cur ->
        cur.primaryImageTag?.let { tag ->
            AuthStore.imageUrl(session.serverUrl, cur.id, tag, maxWidth = 480)
        }
    }
    val dyn = rememberDynamicColors(dynArtUrl)
    val dynBg = dyn?.primary ?: Surface
    val dynAccent = dyn?.secondary ?: Accent

    // BACK on the lyrics view returns to the regular now-playing view; only
    // BACK on the track view continues up the stack (pop via onBack).
    BackHandler(enabled = showLyrics) { showLyrics = false }

    val playPauseFocus = remember { FocusRequester() }
    InitialFocusOnPush(initialFocus, playPauseFocus)

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
    BackHandler(enabled = peekSize > 0) { closePeek() }

    // The lyrics view has no transport row, so a peek cannot survive into it.
    LaunchedEffect(showLyrics) { if (showLyrics && peekSize > 0) closePeek() }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(dynBg, Canvas)))
                // Lyrics view is full-bleed edge to edge; the track view keeps the
                // standard screen insets.
                .padding(
                    horizontal = if (showLyrics) 0.dp else AuraSpacing.npHorizontal,
                    vertical = if (showLyrics) 0.dp else AuraSpacing.xl
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

            NowPlayingTopRow(
                visible = controlsVisible || !showLyrics,
                lyricsMode = showLyrics,
                item = currentItem,
                queue = queueState,
                accent = dynAccent,
                onBack = onBack,
                onOpenQueue = onOpenQueue,
                onToggleLyrics = { showLyrics = !showLyrics }
            )

            if (showLyrics) {
                Box(Modifier.fillMaxSize()) {
                    LyricsPane(
                        app,
                        player,
                        highlightColor = dynAccent,
                        onUserInteraction = { controlsVisible = true }
                    )
                    // Album art + song details, bottom-left, ALWAYS visible —
                    // draws over the lyric lines once the controls hide.
                    NowPlayingChip(app, player)
                }
            } else {
                Row(Modifier.fillMaxSize()) {
                    currentItem?.let { cur ->
                        cur.primaryImageTag?.let { tag ->
                            // Motion artwork for the playing album when the source provides it.
                            // Elsewhere this is exactly the still cover it always was.
                            MotionArtwork(
                                itemId = cur.id,
                                posterModel = AuthStore.imageUrl(
                                    session.serverUrl, cur.id, tag, maxWidth = 800
                                ),
                                contentDescription = cur.name,
                                playing = isPlaying,
                                modifier = Modifier
                                    .width(AuraSizes.artworkLarge)
                                    .height(AuraSizes.artworkLarge)
                                    .clip(RoundedCornerShape(AuraShapes.sm))
                                    .background(SurfaceHigh)
                            )
                        }
                    }
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(start = AuraSpacing.xxl, top = AuraSpacing.npTitleTop)
                    ) {
                        // Big title: shrink to fit, then marquee. Available width
                        // comes from the row's weight(1f) column.
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            AutoFitMarqueeText(
                                text = currentItem?.name ?: "Nothing playing",
                                widthCap = maxWidth,
                                sizes = AuraTypography.npTitleFit,
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
                        Spacer(Modifier.height(AuraSpacing.xl))
                        TransportBar(
                            player, position, duration, isPlaying, playPauseFocus,
                            accent = dynAccent,
                            favorite = currentItem?.let { Favorites.isFavorite(it) } == true,
                            onToggleFavorite = currentItem?.let { cur ->
                                {
                                    npScope.launch {
                                        Favorites.toggle(app.jellyfin.api, session.userId, cur)
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
                            onRadio = currentItem?.let { seed ->
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
            .padding(top = AuraSpacing.xs)
            .clip(RoundedCornerShape(AuraShapes.card))
            .background(if (focused) accent.copy(alpha = 0.25f) else Color.Transparent)
            .border(
                width = if (focused) AuraSizes.focusBorderLoud else 0.dp,
                color = if (focused) accent else Color.Transparent,
                shape = RoundedCornerShape(AuraShapes.card)
            )
            // Before the focus target, like every other focusable in the app.
            .onFocusChanged {
                android.util.Log.d("AURA_NAV", "jump focus $tag focused=${it.isFocused}")
                focused = it.isFocused
            }
            .clickable(onClickLabel = tag) { onClick() }
            .padding(horizontal = AuraSpacing.sm, vertical = AuraSpacing.xs)
    ) {
        androidx.tv.material3.Text(text, fontSize = AuraTypography.subtitle, color = TextSecondary)
    }
}
