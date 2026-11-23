@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.hans.aura.ui.nowplaying

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import com.hans.aura.AuraApp
import com.hans.aura.data.Favorites
import com.hans.aura.data.JellyfinItem
import com.hans.aura.data.Mixes
import com.hans.aura.data.MusicSources
import com.hans.aura.data.Prefs
import com.hans.aura.ui.MirrorLink
import com.hans.aura.ui.common.AutoFitMarqueeText
import com.hans.aura.ui.common.InitialFocusOnPush
import com.hans.aura.ui.common.MotionArtwork
import com.hans.aura.ui.common.resolveArtwork
import com.hans.aura.ui.player.PlayerConnection
import com.hans.aura.ui.theme.AuraDynamicTheme
import com.hans.aura.ui.theme.artworkWash
import com.hans.aura.ui.theme.AuraMotion
import com.hans.aura.ui.theme.AuraShapes
import com.hans.aura.ui.theme.AuraSizes
import com.hans.aura.ui.theme.AuraSpacing
import com.hans.aura.ui.theme.AuraTypography
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
    val artUrl = item?.resolveArtwork()?.url(session.serverUrl, maxWidth = 480)
    AuraDynamicTheme(artUrl) {
        NowPlayingContent(app, player, onBack, initialFocus, onOpenQueue, onOpenAlbum, onOpenArtist, item)
    }
}

@Composable
private fun NowPlayingContent(
    app: AuraApp,
    player: PlayerConnection,
    onBack: () -> Unit,
    initialFocus: Boolean,
    onOpenQueue: () -> Unit,
    onOpenAlbum: (JellyfinItem) -> Unit,
    onOpenArtist: (JellyfinItem) -> Unit,
    item: JellyfinItem?
) {
    val session = app.jellyfin.auth.session ?: return
    val isPlaying by player.isPlaying.collectAsState()
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
    val artwork = currentItem?.resolveArtwork()
    val providerSource = MusicSources.current?.takeIf { it.supportsProviderLyrics }
    var findLyricsOpen by remember(currentItem?.id) { mutableStateOf(false) }
    var lyricsRevision by remember(currentItem?.id) { mutableIntStateOf(0) }

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

    var dimMode by remember { mutableStateOf(Prefs.nowPlayingDimMode) }

    val ambientEnabled = remember { Prefs.ambientNowPlaying }
    var hudInteractionTick by remember { mutableIntStateOf(0) }
    var ambientHidden by remember { mutableStateOf(false) }

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
    // 0 = closed, 3 = peek, 5 = extended peek.
    val queueIndexState by player.queueIndex.collectAsState()
    var peekSize by remember { mutableStateOf(0) }
    var peekFocusToken by remember { mutableStateOf(0) }
    var peekGraceUntil by remember { mutableStateOf(0L) }
    var peekOrigin by remember { mutableStateOf<FocusRequester?>(null) }
    // Everything queued after the playing track, in real play order (the
    // player's order, not the order the user tapped — shuffle included).
    val upcomingEntries by player.upcoming.collectAsState()
    val upcoming = upcomingEntries.map { it.value }

    fun closePeek(log: Boolean = true) {
        if (peekSize == 0) return
        peekSize = 0
        val restoreFocus = peekOrigin ?: playPauseFocus
        peekOrigin = null
        if (log) android.util.Log.d("AURA_PEEK", "closed")
        npScope.launch {
            delay(120)
            repeat(5) {
                if (runCatching { restoreFocus.requestFocus() }.isSuccess) return@launch
                delay(80)
            }
        }
    }

    fun openPeek(origin: FocusRequester): Boolean {
        if (peekSize != 0 || upcoming.isEmpty()) return false
        peekOrigin = origin
        peekSize = 3
        peekFocusToken++
        // The DOWN that opened the peek is still held: swallow its repeats (and
        // any OK release) for a second so the peek cannot skip straight to 5 or
        // click the first row on the same press.
        peekGraceUntil = android.os.SystemClock.uptimeMillis() + 1000L
        android.util.Log.d("AURA_PEEK", "open=3 index=$queueIndexState")
        return true
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

    LaunchedEffect(ambientEnabled, isPlaying, showLyrics, peekSize, findLyricsOpen, hudInteractionTick) {
        ambientHidden = false
        if (ambientEnabled && isPlaying && !showLyrics && peekSize == 0 && !findLyricsOpen) {
            delay(6_000L)
            ambientHidden = true
            android.util.Log.d("AURA_NP", "ambient=on")
        }
    }
    val hudAlpha by animateFloatAsState(
        targetValue = if (ambientHidden) 0f else 1f,
        animationSpec = AuraMotion.effectsDefault,
        label = "npAmbientHudAlpha"
    )
    val wakeKeys = remember { mutableSetOf<Key>() }

    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.key in wakeKeys) {
                    if (event.type == KeyEventType.KeyUp) wakeKeys.remove(event.key)
                    return@onPreviewKeyEvent true
                }
                if (event.type == KeyEventType.KeyDown) {
                    hudInteractionTick++
                    if (ambientHidden) {
                        ambientHidden = false
                        android.util.Log.d("AURA_NP", "ambient=off")
                        runCatching { playPauseFocus.requestFocus() }
                        if (event.key != Key.Back) {
                            wakeKeys.add(event.key)
                            return@onPreviewKeyEvent true
                        }
                    }
                }
                false
            }
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(if (dimMode) colors.background else colors.artworkWash, colors.surface)))
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
            LaunchedEffect(showLyrics, controlsVisible, findLyricsOpen) {
                if (showLyrics && controlsVisible && !findLyricsOpen) {
                    delay(6000)
                    controlsVisible = false
                }
            }
            if (!showLyrics) controlsVisible = true

            Box(Modifier.graphicsLayer { alpha = if (showLyrics) 1f else hudAlpha }) {
                NowPlayingTopRow(
                    visible = controlsVisible || !showLyrics,
                    lyricsMode = showLyrics,
                    item = currentItem,
                    queue = queueState,
                    onBack = onBack,
                    onOpenQueue = onOpenQueue,
                    onFindLyrics = if (providerSource != null && currentItem != null) {
                        { findLyricsOpen = true }
                    } else null,
                    onToggleLyrics = { showLyrics = !showLyrics }
                )
            }

            if (showLyrics) {
                Box(Modifier.fillMaxSize()) {
                    key(lyricsRevision) {
                        LyricsPane(
                            app,
                            player,
                            onUserInteraction = { controlsVisible = true }
                        )
                    }
                    // Album art + song details, bottom-left, ALWAYS visible —
                    // draws over the lyric lines once the controls hide.
                    NowPlayingChip(currentItem, artwork?.url(session.serverUrl, maxWidth = 200))
                }
            } else {
                Row(Modifier.fillMaxSize()) {
                    if (artwork != null) {
                        // Motion artwork for the playing album when the source provides it.
                        // Elsewhere this is exactly the still cover it always was.
                        MotionArtwork(
                            itemId = artwork.itemId,
                            posterModel = artwork.url(session.serverUrl, maxWidth = 800),
                            contentDescription = currentItem?.name,
                            playing = isPlaying,
                            modifier = Modifier
                                .width(AuraSizes.artworkLarge)
                                .height(AuraSizes.artworkLarge)
                                .clip(AuraShapes.card)
                                .background(androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh)
                        )
                    }
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(start = AuraSpacing.xxl, top = AuraSpacing.npTitleTop)
                            .graphicsLayer { alpha = hudAlpha }
                    ) {
                        // Big title: shrink to fit, then marquee. Available width
                        // comes from the row's weight(1f) column.
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            AutoFitMarqueeText(
                                text = currentItem?.name ?: "Nothing playing",
                                widthCap = maxWidth,
                                sizes = AuraTypography.npTitleFit,
                                color = colors.onSurface
                            )
                        }
                        // Album / artist lines open their detail screens.
                        albumItem?.let { ab ->
                            JumpText(tag = "album", text = ab.name.ifBlank { "Album" }) {
                                onOpenAlbum(ab)
                            }
                        }
                        artistItem?.let { ar ->
                            JumpText(tag = "artist", text = ar.name.ifBlank { "Artist" }) {
                                onOpenArtist(ar)
                            }
                        }
                        Spacer(Modifier.height(AuraSpacing.xl))
                        LiveTransportBar(
                            player = player,
                            isPlaying = isPlaying,
                            playPauseFocus = playPauseFocus,
                            favorite = currentItem?.let { Favorites.isFavorite(it) } == true,
                            onToggleFavorite = currentItem?.let { cur ->
                                {
                                    npScope.launch {
                                        Favorites.toggle(app.jellyfin.api, session.userId, cur)
                                    }
                                }
                            },
                            onOpenPeek = ::openPeek,
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
                            },
                            dimMode = dimMode,
                            onToggleDimMode = {
                                dimMode = !dimMode
                                Prefs.nowPlayingDimMode = dimMode
                            }
                        )
                    }
                }
            }
        }
        if (!showLyrics && hudAlpha < 0.99f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 1f - hudAlpha }
            ) {
                NowPlayingChip(currentItem, artwork?.url(session.serverUrl, maxWidth = 200))
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
                onJump = { row ->
                    val qIndex = upcomingEntries.getOrNull(row)?.index ?: return@QueuePeekOverlay
                    android.util.Log.d("AURA_PEEK", "jump index=$qIndex")
                    closePeek(log = false)
                    player.skipToQueue(qIndex)
                }
            )
        }
        if (findLyricsOpen && providerSource != null && currentItem != null) {
            ProviderLyricsDialog(
                source = providerSource,
                track = currentItem,
                onDismiss = { findLyricsOpen = false },
                onSaved = {
                    findLyricsOpen = false
                    lyricsRevision++
                    showLyrics = true
                }
            )
        }
    }
}

/** Clickable "jump to album/artist" line with a clear focus state. */
@Composable
private fun JumpText(tag: String, text: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            // Pill padding hangs into the gutter so text aligns with the title.
            .offset(x = -AuraSpacing.sm)
            .padding(top = AuraSpacing.xs)
            .clip(AuraShapes.control)
            .background(if (focused) colors.secondaryContainer else Color.Transparent)
            .border(
                width = if (focused) AuraSizes.focusBorderLoud else 0.dp,
                color = if (focused) colors.primary else Color.Transparent,
                shape = AuraShapes.control
            )
            // Before the focus target, like every other focusable in the app.
            .onFocusChanged {
                android.util.Log.d("AURA_NAV", "jump focus $tag focused=${it.isFocused}")
                focused = it.isFocused
            }
            .clickable(onClickLabel = tag) { onClick() }
            .padding(horizontal = AuraSpacing.sm, vertical = AuraSpacing.xs)
    ) {
        androidx.tv.material3.Text(text, fontSize = AuraTypography.subtitle, color = if (focused) colors.onSecondaryContainer else colors.primary)
    }
}
