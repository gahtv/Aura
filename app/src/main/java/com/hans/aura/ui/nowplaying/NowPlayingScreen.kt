@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.hans.aura.ui.nowplaying

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import com.hans.aura.AuraApp
import com.hans.aura.data.Favorites
import com.hans.aura.model.Album
import com.hans.aura.model.Artist
import com.hans.aura.model.Track
import com.hans.aura.data.Mixes
import com.hans.aura.data.MusicSources
import com.hans.aura.data.Prefs
import com.hans.aura.ui.MirrorLink
import com.hans.aura.ui.common.AutoFitMarqueeText
import com.hans.aura.ui.common.InitialFocusOnPush
import com.hans.aura.ui.common.MotionArtwork
import com.hans.aura.ui.common.LocalArtworkTransition
import com.hans.aura.ui.common.artworkRequest
import com.hans.aura.ui.common.requestFocusOnFrame
import com.hans.aura.ui.common.sharedArtwork
import com.hans.aura.ui.common.url
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
    onOpenQueue: () -> Unit,
    onOpenAlbum: (Album) -> Unit = {},
    onOpenArtist: (Artist) -> Unit = {}
) {
    val session = app.jellyfin.auth.session ?: return
    val item by player.current.collectAsState()
    val artUrl = item?.albumArtwork?.url(session.serverUrl, maxWidth = 480)
    AuraDynamicTheme(artUrl) {
        NowPlayingContent(app, player, onBack, onOpenQueue, onOpenAlbum, onOpenArtist, item)
    }
}

@Composable
private fun NowPlayingContent(
    app: AuraApp,
    player: PlayerConnection,
    onBack: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenAlbum: (Album) -> Unit,
    onOpenArtist: (Artist) -> Unit,
    item: Track?
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
    val artwork = currentItem?.albumArtwork
    val providerSource = MusicSources.current?.takeIf { it.supportsProviderLyrics }
    var findLyricsOpen by remember(currentItem?.id) { mutableStateOf(false) }
    var lyricsRevision by remember(currentItem?.id) { mutableIntStateOf(0) }

    // Jump targets: stub items (id + name + type) are enough — the album and
    // artist screens refetch the full item by id on entry.
    val albumItem = currentItem?.let { cur ->
        cur.albumId?.let { aid ->
            Album(id = aid, name = cur.album ?: "")
        }
    }
    val artistEntry = currentItem?.albumArtists?.firstOrNull()
        ?: currentItem?.artists?.firstOrNull()
    val artistItem = artistEntry?.let {
        Artist(id = it.id, name = it.name)
    }

    var dimMode by remember { mutableStateOf(Prefs.nowPlayingDimMode) }
    val dimFraction by animateFloatAsState(
        targetValue = if (dimMode) 1f else 0f,
        animationSpec = AuraMotion.scheme.slowEffectsSpec(),
        label = "npDim"
    )

    val ambientEnabled = remember { Prefs.ambientNowPlaying }
    var hudInteractionTick by remember { mutableIntStateOf(0) }
    var ambientHidden by remember { mutableStateOf(false) }
    // Per-track ambient lyrics verdict. null = still loading/unknown (keep the
    // standard cover-left layout), true = lyrics show on the right, false =
    // confirmed lyric-less (the cover slides to screen centre). Survives
    // ambient cycles for the same track; resets on track change.
    var ambientHasLyrics by remember(currentItem?.id) { mutableStateOf<Boolean?>(null) }

    // BACK on the lyrics view returns to the regular now-playing view; only
    // BACK on the track view continues up the stack (pop via onBack).
    BackHandler(enabled = showLyrics) { showLyrics = false }

    val playPauseFocus = remember { FocusRequester() }
    InitialFocusOnPush(true, playPauseFocus)

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
            restoreFocus.requestFocusOnFrame()
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
        animationSpec = AuraMotion.scheme.defaultEffectsSpec(),
        label = "npAmbientHudAlpha"
    )
    // Composition gate for the ambient extras: derived, so the enclosing scope
    // recomposes only when the boolean flips, not on every fade frame.
    val ambientExtrasVisible by remember { derivedStateOf { hudAlpha < 0.99f } }
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
                .background(Brush.verticalGradient(listOf(lerp(colors.artworkWash, colors.background, dimFraction), colors.surface)))
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

            // Column, not Box: the top row emits its pills AND the spacer below
            // them. A Box stacks children — the spacer overlapped the pill row
            // and never took vertical space, so the bar sat on the album cover
            // with a zero gap no matter how large the spacer was.
            Column(Modifier.graphicsLayer { alpha = if (showLyrics) 1f else hudAlpha }) {
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
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    // Ambient with the lyrics CONFIRMED absent: slide the whole
                    // cover block (art plus the info under it) to the horizontal
                    // centre of the screen instead of leaving the right half
                    // empty. An offset, not a re-layout — the HUD column behind
                    // keeps its exact size, so waking the HUD never reflows the
                    // title or the transport.
                    val ambientSlide by animateDpAsState(
                        targetValue = if (ambientExtrasVisible && ambientHasLyrics == false) {
                            ((maxWidth - AuraSizes.artworkLarge) / 2).coerceAtLeast(0.dp)
                        } else 0.dp,
                        animationSpec = AuraMotion.scheme.defaultSpatialSpec(),
                        label = "npAmbientCoverSlide"
                    )
                    Row(Modifier.fillMaxSize()) {
                        // Cover column: the artwork plus the ambient info block
                        // that fades in UNDER it while the HUD fades out. This
                        // replaces the old bottom-left chip, whose mini cover made
                        // ambient mode show the same album art twice.
                        Column(Modifier.offset(x = ambientSlide)) {
                            if (artwork != null) {
                                // Motion artwork for the playing album when the source provides it.
                                // Elsewhere this is exactly the still cover it always was.
                                MotionArtwork(
                                    itemId = artwork.itemId,
                                    posterModel = artworkRequest(
                                        artwork.url(session.serverUrl, maxWidth = 800),
                                        placeholderKey = artwork.url(session.serverUrl, maxWidth = 480)
                                    ),
                                    contentDescription = currentItem?.name,
                                    playing = isPlaying,
                                    modifier = Modifier
                                        .width(AuraSizes.artworkLarge)
                                        .height(AuraSizes.artworkLarge)
                                        .then(
                                            if (LocalArtworkTransition.current != null && artwork.itemId == currentItem?.albumId) {
                                                Modifier.sharedArtwork(artwork.itemId, AuraShapes.cardRadius)
                                            } else Modifier.clip(AuraShapes.card)
                                        )
                                        .background(androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh)
                                )
                            }
                            // Ambient info, always under the cover slot — a track
                            // with no artwork still shows its title/artist here
                            // (the old chip never depended on art either).
                            if (ambientExtrasVisible) {
                                Column(
                                    Modifier
                                        .width(AuraSizes.artworkLarge)
                                        .graphicsLayer { alpha = 1f - hudAlpha }
                                        .padding(top = AuraSpacing.md)
                                ) {
                                    // Same shrink-then-marquee treatment as the
                                    // full Now Playing title.
                                    AutoFitMarqueeText(
                                        text = currentItem?.name ?: "",
                                        widthCap = AuraSizes.artworkLarge,
                                        sizes = AuraTypography.chipTitleFit,
                                        color = colors.onSurface
                                    )
                                    Spacer(Modifier.height(AuraSpacing.xxs))
                                    androidx.tv.material3.Text(
                                        currentItem?.artistNames ?: "",
                                        color = colors.onSurfaceVariant,
                                        fontSize = AuraTypography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
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
                                            app.container.media.toggleFavorite(cur)
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
                                                media = app.container.media,
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
        }
        // Ambient extras (track view only): the HUD's own info column fades to
        // nothing, so the cover column carries the track info under the art
        // and this right-hand pane carries the LYRICS when any exist. When the
        // pane confirms there are none, the cover instead slides to the screen
        // centre (ambientSlide above) — no empty right half.
        if (!showLyrics && ambientExtrasVisible) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 1f - hudAlpha }
            ) {
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .fillMaxWidth(0.46f)
                        .padding(end = AuraSpacing.npHorizontal)
                ) {
                    key(lyricsRevision) {
                        // onAvailability: "no lyrics" confirms the centre slide;
                        // "has lyrics" keeps the cover left with this pane as
                        // the right half of the ambient view.
                        LyricsPane(
                            app,
                            player,
                            ambient = true,
                            onAvailability = { ambientHasLyrics = it }
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
