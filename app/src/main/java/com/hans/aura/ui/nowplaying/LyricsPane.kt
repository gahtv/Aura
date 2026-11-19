package com.hans.aura.ui.nowplaying

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.hans.aura.AuraApp
import com.hans.aura.data.AuthStore
import com.hans.aura.data.JellyfinItem
import com.hans.aura.data.Lrclib
import com.hans.aura.data.LyricLine
import com.hans.aura.data.LyricVoice
import com.hans.aura.data.LyricsStore
import com.hans.aura.data.MusicSources
import com.hans.aura.data.Prefs
import com.hans.aura.data.TimedLyricLine
import com.hans.aura.data.karaokeSpans
import com.hans.aura.ui.common.AutoFitMarqueeText
import com.hans.aura.ui.player.PlayerConnection
import com.hans.aura.ui.theme.Accent
import com.hans.aura.ui.theme.AuraColors
import com.hans.aura.ui.theme.AuraShapes
import com.hans.aura.ui.theme.AuraSizes
import com.hans.aura.ui.theme.AuraSpacing
import com.hans.aura.ui.theme.AuraTypography
import com.hans.aura.ui.theme.SurfaceHigh
import com.hans.aura.ui.theme.TextPrimary
import com.hans.aura.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

internal fun lyricVoiceStyle(voice: LyricVoice?, multipleVoices: Boolean, highlight: Color): Pair<TextAlign, Color> {
    if (!multipleVoices) return TextAlign.Center to highlight
    return when (voice?.kind) {
        LyricVoice.Kind.PRIMARY -> TextAlign.Start to highlight
        LyricVoice.Kind.SECONDARY -> {
            val high = max(highlight.red, max(highlight.green, highlight.blue))
            val low = min(highlight.red, min(highlight.green, highlight.blue))
            val delta = high - low
            val hue = when {
                delta == 0f -> 0f
                high == highlight.red -> 60f * ((highlight.green - highlight.blue) / delta % 6f)
                high == highlight.green -> 60f * ((highlight.blue - highlight.red) / delta + 2f)
                else -> 60f * ((highlight.red - highlight.green) / delta + 4f)
            }
            TextAlign.End to Color.hsv((hue + 490f) % 360f,
                (if (high == 0f) 0f else delta / high).coerceIn(0.35f, 0.7f), high.coerceAtLeast(0.85f), highlight.alpha)
        }
        else -> TextAlign.Center to highlight
    }
}

/**
 * Line-synced lyrics, top-anchored: the current line sits near the top with
 * the three previous lines above it, and auto-follows playback. The album
 * art/title chip is drawn by the parent OVER this pane. Manual D-pad scrolling
 * pauses the auto-follow; it resumes after 4s of inactivity, on a seek, or on
 * a track change. Any key press is surfaced to the parent so it can re-show
 * the hidden transport controls.
 *
 * D-pad LEFT/RIGHT nudge the per-track lyric offset by ±0.5 s while the pane
 * holds focus (persisted in Prefs under `lyrics_offset_<trackId>`), with a
 * short-lived "offset +0.5s" chip as feedback.
 *
 * When the server has no lyrics the pane shows the track's metadata plus a
 * real message instead of blank space; with Settings > "Look up lyrics online"
 * on, LRCLIB is asked first and the result fed through the same LRC parser.
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
    val trackId = item?.id.orEmpty()

    var lines by remember(item?.id) { mutableStateOf<List<LyricLine>?>(null) }
    // Word-timed copy of the same lines, when the source can time words. Null
    // means "no karaoke" and the pane behaves exactly as it always has.
    var timedLines by remember(item?.id) { mutableStateOf<List<TimedLyricLine>?>(null) }
    // Whether the online fallback was consulted for THIS track, and whether it
    // produced anything — drives the hint under the empty state.
    var webChecked by remember(item?.id) { mutableStateOf(false) }
    var webFound by remember(item?.id) { mutableStateOf(false) }
    // Fresh scroll state per track: without keying on the item id, a track
    // change keeps the previous song's scroll offset until the new song's
    // first line fires a scroll, so the pane lingers at the old end position.
    val listState = remember(item?.id) { LazyListState() }
    val scope = rememberCoroutineScope()
    var followPaused by remember(item?.id) { mutableStateOf(false) }

    var offsetMs by remember(item?.id) { mutableStateOf(Prefs.lyricsOffsetMs(trackId)) }
    // Chip TEXT is kept after it hides so the fade-out does not empty the pill.
    var offsetChip by remember(item?.id) { mutableStateOf<String?>(null) }
    var chipVisible by remember(item?.id) { mutableStateOf(false) }
    var chipTick by remember(item?.id) { mutableIntStateOf(0) }

    LaunchedEffect(item?.id) {
        val cur = item ?: return@LaunchedEffect
        lines = null
        timedLines = null
        webChecked = false
        webFound = false
        // Word-timed lyrics first: they carry the same lines plus per-word
        // timestamps, so the pane can highlight as the line is sung. A source
        // that has none (or fails to) answers null and nothing changes.
        val timed = runCatching { MusicSources.current?.lyricsTimed(cur.id) }.getOrNull()
        val sourceWeb = (MusicSources.current as? com.hans.aura.data.JellyfinSource)
            ?.cachedWebLyricsFound(cur.id)
        webChecked = sourceWeb != null
        webFound = sourceWeb == true
        if (!timed.isNullOrEmpty()) {
            timedLines = timed
            lines = timed.map { LyricLine(startMs = it.startMs, text = it.text, isSynced = true, words = it.words, voice = it.voice) }
            android.util.Log.i("AURA_LYRICS", "timed item=${cur.id} lines=${timed.size}")
            return@LaunchedEffect
        }
        val local = LyricsStore.fetchPreferred(app.jellyfin.api, cur.id)
        if (local.isNotEmpty()) {
            lines = local
            if (local.any { it.words.isNotEmpty() }) {
                timedLines = local.map { TimedLyricLine(it.startMs, it.text, words = it.words, voice = it.voice) }
            }
            return@LaunchedEffect
        }
        if (!Prefs.lyricsWebFallback) {
            lines = emptyList()
            return@LaunchedEffect
        }
        // In-process cache inside Lrclib: re-entering the pane does not re-query.
        val remote = Lrclib.lookup(cur)
        webChecked = true
        webFound = remote.isNotEmpty()
        lines = remote
        if (remote.any { it.words.isNotEmpty() }) {
            timedLines = remote.map { TimedLyricLine(it.startMs, it.text, words = it.words, voice = it.voice) }
        }
    }

    // +offset = the highlighted line is one step EARLIER than the audio clock.
    val effectivePosition = (position + offsetMs).coerceAtLeast(0L)
    val currentLineIndex = remember(lines, effectivePosition) {
        computeCurrentLine(lines, effectivePosition)
    }

    // Anchor offset: the current line lands ~3 line-heights below the top, so
    // exactly the previous three lines stay visible above it.
    // Line box (26/22sp text + 20dp vertical padding) measures ~69dp pitch on
    // TV; 3*70 leaves a small margin so the third previous line never clips.
    val anchorOffsetPx = with(LocalDensity.current) { (3 * AuraSizes.lyricLinePitch.value).dp.roundToPx() }

    fun pauseFollow() {
        followPaused = true
        scope.launch {
            delay(4000)
            followPaused = false
        }
    }

    fun nudge(deltaMs: Long) {
        if (trackId.isBlank()) return
        val next = (Prefs.lyricsOffsetMs(trackId) + deltaMs)
            .coerceIn(-Prefs.LYRICS_OFFSET_MAX_MS, Prefs.LYRICS_OFFSET_MAX_MS)
        offsetMs = next
        Prefs.setLyricsOffsetMs(trackId, next)
        offsetChip = if (next == 0L) "offset reset"
        else "offset " + String.format(Locale.US, "%+.1fs", next / 1000.0)
        chipVisible = true
        chipTick++
        android.util.Log.i("AURA_LYRICS", "offset item=$trackId ms=$next")
    }

    // The feedback chip fades after a couple of seconds of no further presses.
    LaunchedEffect(chipTick) {
        if (!chipVisible) return@LaunchedEffect
        delay(2200)
        chipVisible = false
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
        // Second snap: the first pass positions with ESTIMATED offsets for
        // unmeasured items, so the current line can land a line or two low.
        // A second call re-anchors with the now-measured sizes.
        listState.scrollToItem(index = target + 1, scrollOffset = -anchorOffsetPx)
    }

    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                onUserInteraction()
                // DOWN/UP scroll (and pause auto-follow) and LEFT/RIGHT nudge
                // the offset, no matter which child inside the pane holds
                // focus. KeyDown only — the matching KeyUp must not step twice.
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
                        // Only when there is something to sync: with no lyrics
                        // LEFT/RIGHT must stay ordinary focus movement.
                        Key.DirectionLeft -> if (lines.isNullOrEmpty()) false else {
                            nudge(-500L)
                            true
                        }
                        Key.DirectionRight -> if (lines.isNullOrEmpty()) false else {
                            nudge(500L)
                            true
                        }
                        else -> false
                    }
                } else false
            }
    ) {
        val loadedLines = lines
        val multipleVoices = loadedLines.orEmpty().mapNotNull { it.voice?.id }.distinct().size >= 2
        when {
            loadedLines == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                androidx.tv.material3.Text(
                    "Loading lyrics\u2026", color = TextSecondary, fontSize = AuraTypography.body
                )
            }
            loadedLines.isEmpty() -> NoLyricsState(
                app = app,
                item = item,
                accent = highlightColor,
                webChecked = webChecked,
                webFound = webFound,
                webEnabled = Prefs.lyricsWebFallback
            )
            else -> LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .focusable(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Small head spacer so the first line never glues to the top
                // edge; the anchor then shows the previous lines above current.
                item { Spacer(Modifier.height(AuraSpacing.screenHorizontal)) }
                items(loadedLines.size) { i ->
                    val line = loadedLines[i]
                    val (alignment, lineHighlight) = lyricVoiceStyle(line.voice, multipleVoices, highlightColor)
                    val isCurrent = i == currentLineIndex
                    val color by animateColorAsState(
                        when {
                            isCurrent -> lineHighlight
                            currentLineIndex in 0 until i -> TextSecondary
                            else -> TextPrimary
                        },
                        label = "lyricLine"
                    )
                    // Karaoke: on the line being sung, the words whose start_ms
                    // has passed take the accent and the rest of the line stays
                    // muted. Every other line is drawn exactly as before.
                    val timed = timedLines?.getOrNull(i)
                    val karaoke = isCurrent && timed != null
                    val text = if (karaoke) {
                        karaokeLine(timed!!, effectivePosition, lineHighlight)
                    } else {
                        AnnotatedString(line.text.ifBlank { "\u00A0" })
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(
                                if (isCurrent) lineHighlight.copy(alpha = 0.13f)
                                else Color.Transparent
                            )
                            .clickable(enabled = line.isSynced) {
                                player.seekTo(line.startMs)
                                followPaused = false
                                onUserInteraction()
                            }
                            .padding(vertical = AuraSpacing.lyricLineVertical)
                            .then(if (multipleVoices) Modifier.padding(horizontal = AuraSpacing.screenHorizontal) else Modifier)
                    ) {
                        androidx.tv.material3.Text(
                            text,
                            color = if (karaoke) TextSecondary else color,
                            fontSize = if (isCurrent) AuraTypography.headline else AuraTypography.title,
                            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                            textAlign = alignment,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                // Enough trailing scroll range that the FINAL line can reach the
                // top anchor (the bottom-left chip may overlap it — accepted).
                item { Spacer(Modifier.height(AuraSizes.lyricsTailSpace)) }
            }
        }

        // Offset feedback: bottom-right, over the empty space beside the
        // bottom-left now-playing chip. Never focusable — pure indicator.
        AnimatedVisibility(
            visible = chipVisible,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = AuraSpacing.npHorizontal, bottom = AuraSpacing.xxl),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            val shape = RoundedCornerShape(AuraShapes.sm)
            androidx.tv.material3.Text(
                text = offsetChip.orEmpty(),
                color = AuraColors.OnAccentText,
                fontSize = AuraTypography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(shape)
                    .background(highlightColor)
                    .padding(horizontal = AuraSpacing.md, vertical = AuraSpacing.xs)
            )
        }
    }
}

/**
 * Empty state that reads as a real screen: the track's own metadata card plus
 * an explicit "no synced lyrics" line, and — when the online lookup is on and
 * came back empty — a hint that LRCLIB found nothing either.
 */
@Composable
private fun NoLyricsState(
    app: AuraApp,
    item: JellyfinItem?,
    accent: Color,
    webChecked: Boolean,
    webFound: Boolean,
    webEnabled: Boolean
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = AuraSpacing.npHorizontal),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
    ) {
        if (item != null) {
            LyricsTrackCard(app = app, item = item, accent = accent)
            Spacer(Modifier.height(AuraSpacing.lg))
        }
        androidx.tv.material3.Text(
            "No synced lyrics for this track",
            color = TextPrimary,
            fontSize = AuraTypography.title
        )
        Spacer(Modifier.height(AuraSpacing.xs))
        androidx.tv.material3.Text(
            when {
                !webEnabled ->
                    "Turn on \u201CLook up lyrics online\u201D in Settings to search LRCLIB for lyrics."
                webChecked && !webFound ->
                    "LRCLIB has no lyrics for this track either."
                else -> "Looking online\u2026"
            },
            color = TextSecondary,
            fontSize = AuraTypography.bodySmall,
            textAlign = TextAlign.Center
        )
    }
}

/** Album art + title/artist/album card for the no-lyrics state (not focusable). */
@Composable
private fun LyricsTrackCard(app: AuraApp, item: JellyfinItem, accent: Color) {
    val session = app.jellyfin.auth.session
    val shape = RoundedCornerShape(AuraShapes.lg)
    Row(
        Modifier
            .widthIn(max = AuraSizes.lyricCardWidth)
            .clip(shape)
            .background(AuraColors.CardScrim, shape)
            .padding(AuraSpacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        session?.let { s ->
            item.primaryImageTag?.let { tag ->
                AsyncImage(
                    model = AuthStore.imageUrl(s.serverUrl, item.id, tag, maxWidth = 240),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(AuraSizes.artworkChipLarge)
                        .clip(RoundedCornerShape(AuraShapes.sm))
                        .background(SurfaceHigh)
                )
                Spacer(Modifier.width(AuraSpacing.md))
            }
        }
        Column {
            AutoFitMarqueeText(
                text = item.name,
                widthCap = 340.dp,
                sizes = AuraTypography.lyricCardFit,
                color = TextPrimary
            )
            if (item.artistNames.isNotBlank()) {
                androidx.tv.material3.Text(
                    item.artistNames,
                    color = accent,
                    fontSize = AuraTypography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            item.album?.takeIf { it.isNotBlank() }?.let { album ->
                androidx.tv.material3.Text(
                    album,
                    color = TextSecondary,
                    fontSize = AuraTypography.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
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

/**
 * One word-timed line with the sung part in [accent] and the rest muted.
 *
 * The split is [karaokeSpans]' job (pure, JVM-tested); this only turns the runs
 * into spans, so the highlight follows the same 500 ms position ticker the line
 * jump uses — no extra clock and no per-word animation.
 */
private fun karaokeLine(line: TimedLyricLine, positionMs: Long, accent: Color): AnnotatedString =
    buildAnnotatedString {
        karaokeSpans(line, positionMs).forEach { span ->
            withStyle(SpanStyle(color = if (span.sung) accent else TextSecondary)) {
                append(span.text)
            }
        }
    }
