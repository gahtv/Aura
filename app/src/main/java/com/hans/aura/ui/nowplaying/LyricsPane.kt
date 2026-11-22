package com.hans.aura.ui.nowplaying

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.hans.aura.AuraApp
import com.hans.aura.data.JellyfinItem
import com.hans.aura.data.KaraokeFill
import com.hans.aura.data.Lrclib
import com.hans.aura.data.LyricBreak
import com.hans.aura.data.LyricAdlib
import com.hans.aura.data.LyricCredits
import com.hans.aura.data.LyricLine
import com.hans.aura.data.LyricVoice
import com.hans.aura.data.LyricsResult
import com.hans.aura.data.LyricsStore
import com.hans.aura.data.MusicSources
import com.hans.aura.data.Prefs
import com.hans.aura.data.TimedLyricLine
import com.hans.aura.data.karaokeFills
import com.hans.aura.data.lyricBreaks
import com.hans.aura.data.toLyricLine
import com.hans.aura.data.toTimedLyricLine
import com.hans.aura.ui.common.AutoFitMarqueeText
import com.hans.aura.ui.common.resolveArtwork
import com.hans.aura.ui.player.PlayerConnection
import com.hans.aura.ui.theme.Accent
import com.hans.aura.ui.theme.AuraColors
import com.hans.aura.ui.theme.AuraMotion
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
    val position = player.positionMs.collectAsState()
    val isPlaying by player.isPlaying.collectAsState()
    val positionAnchor = remember(position.value, isPlaying) { SystemClock.uptimeMillis() }
    val trackId = item?.id.orEmpty()

    var lines by remember(item?.id) { mutableStateOf<List<LyricLine>?>(null) }
    // Word-timed copy of the same lines, when the source can time words. Null
    // means "no karaoke" and the pane behaves exactly as it always has.
    var timedLines by remember(item?.id) { mutableStateOf<List<TimedLyricLine>?>(null) }
    // Whether the online fallback was consulted for THIS track, and whether it
    // produced anything — drives the hint under the empty state.
    var webChecked by remember(item?.id) { mutableStateOf(false) }
    var webFound by remember(item?.id) { mutableStateOf(false) }
    var instrumental by remember(item?.id) { mutableStateOf(false) }
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
        instrumental = false
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
            lines = timed.map { it.toLyricLine() }
            android.util.Log.i("AURA_LYRICS", "timed item=${cur.id} lines=${timed.size}")
            return@LaunchedEffect
        }
        val local = LyricsStore.fetchPreferred(app.jellyfin.api, cur.id)
        if (local.isNotEmpty()) {
            lines = local
            if (local.any { it.words.isNotEmpty() }) {
                timedLines = local.map { it.toTimedLyricLine() }
            }
            return@LaunchedEffect
        }
        if (!Prefs.lyricsWebFallback) {
            lines = emptyList()
            return@LaunchedEffect
        }
        // In-process cache inside Lrclib: re-entering the pane does not re-query.
        val result = Lrclib.lookupResult(cur)
        val remote = result.lines
        instrumental = result == LyricsResult.Instrumental
        webChecked = true
        webFound = remote.isNotEmpty()
        lines = remote
        if (remote.any { it.words.isNotEmpty() }) {
            timedLines = remote.map { it.toTimedLyricLine() }
        }
    }

    // +offset = the highlighted line is one step EARLIER than the audio clock.
    val effectivePosition = (position.value + offsetMs).coerceAtLeast(0L)
    val currentLineIndex = remember(lines, effectivePosition) {
        computeCurrentLine(lines, effectivePosition)
    }
    val breaks = remember(lines) { lyricBreaks(lines.orEmpty()) }
    val activeBreak = breaks.firstOrNull { effectivePosition in it }
    val rows = remember(lines, breaks) { lyricRows(lines.orEmpty().size, breaks) }
    // During a break the dots take the current line's anchor slot.
    val followRow = activeBreak?.let { rows.indexOf(LyricRow.Break(it)) }
        ?: rows.indexOf(LyricRow.Line(currentLineIndex.coerceAtLeast(0)))
    val hasTimedAdlibs = remember(lines) { lines.orEmpty().any { it.adlib?.isTimed == true || it.translation?.isTimed == true || it.romanization?.isTimed == true } }
    val adlibPosition = remember(trackId) { mutableStateOf<Long?>(null) }
    LaunchedEffect(player, adlibPosition, hasTimedAdlibs, isPlaying) {
        adlibPosition.value = null
        if (hasTimedAdlibs && isPlaying) {
            // The regular progress ticker can skip several short syllables.
            while (true) {
                adlibPosition.value = player.currentPositionMs()
                delay(50L)
            }
        }
    }

    // Anchor about three standard line heights from the top; wrapped lyrics
    // and adlibs can take up more of the space above the current line.
    val anchorOffsetPx = with(LocalDensity.current) { (3 * AuraSizes.lyricLinePitch.value).dp.roundToPx() }
    // Plain-text lyrics have no clock to follow or offset to nudge: they read as a static page.
    val synced = lines?.firstOrNull()?.isSynced == true

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

    LaunchedEffect(lines, followRow, followPaused) {
        if (!synced || followPaused) return@LaunchedEffect
        // Before the first synced line's timestamp (currentLineIndex == -1)
        // anchor the FIRST line, or the intro dots, instead.
        listState.animateScrollToItem(
            index = followRow + 1, // +1: head spacer item
            scrollOffset = -anchorOffsetPx
        )
        // Second snap: the first pass positions with ESTIMATED offsets for
        // unmeasured items, so the current line can land a line or two low.
        // A second call re-anchors with the now-measured sizes.
        listState.scrollToItem(index = followRow + 1, scrollOffset = -anchorOffsetPx)
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
                        // Only when there is something to sync: with no synced
                        // lyrics LEFT/RIGHT must stay ordinary focus movement.
                        Key.DirectionLeft -> if (!synced) false else {
                            nudge(-500L)
                            true
                        }
                        Key.DirectionRight -> if (!synced) false else {
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
                webEnabled = Prefs.lyricsWebFallback,
                instrumental = instrumental
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
                items(
                    count = rows.size,
                    key = { r ->
                        when (val row = rows[r]) {
                            is LyricRow.Line -> "line_${row.index}"
                            is LyricRow.Break -> "break_${row.gap.afterLine}"
                        }
                    }
                ) { r ->
                    val row = rows[r]
                    if (row is LyricRow.Break) {
                        val next = loadedLines.getOrNull(row.gap.afterLine + 1)
                        val (alignment, _) = lyricVoiceStyle(next?.voice, multipleVoices, highlightColor)
                        LyricBreakDots(
                            visible = row.gap == activeBreak,
                            progress = row.gap.progress(effectivePosition),
                            alignment = when (alignment) {
                                TextAlign.Start -> Alignment.Start
                                TextAlign.End -> Alignment.End
                                else -> Alignment.CenterHorizontally
                            },
                            modifier = if (multipleVoices) Modifier.padding(horizontal = AuraSpacing.screenHorizontal) else Modifier
                        )
                        return@items
                    }
                    val i = (row as LyricRow.Line).index
                    val line = loadedLines[i]
                    val (alignment, lineHighlight) = lyricVoiceStyle(line.voice, multipleVoices, highlightColor)
                    // The line that just ended rests with the others through a break.
                    val isCurrent = i == currentLineIndex && activeBreak == null
                    // Line-only timestamps do not carry karaoke word timing.
                    val timed = timedLines?.getOrNull(i)?.takeIf { isCurrent && it.words.isNotEmpty() }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .then(if (multipleVoices && isCurrent) {
                                Modifier.background(lineHighlight.copy(alpha = 0.13f))
                            } else Modifier)
                            .clickable(enabled = line.isSynced) {
                                player.seekTo(line.startMs)
                                followPaused = false
                                onUserInteraction()
                            }
                            .padding(vertical = AuraSpacing.lyricLineVertical)
                            .then(if (multipleVoices) Modifier.padding(horizontal = AuraSpacing.screenHorizontal) else Modifier)
                    ) {
                        if (timed != null) {
                            KaraokeLine(timed, effectivePosition, loadedLines.getOrNull(i + 1)?.startMs,
                                isPlaying, positionAnchor, lineHighlight, alignment)
                        } else {
                            androidx.tv.material3.Text(
                                AnnotatedString(line.text.ifBlank { "\u00A0" }),
                                color = TextPrimary,
                                fontSize = if (isCurrent) AuraTypography.headline else AuraTypography.title,
                                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                textAlign = alignment,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        val adlib = line.adlib
                        if (adlib != null && adlib.text.isNotBlank()) {
                            AdlibText(adlib, adlibPosition, position, offsetMs, isCurrent, alignment, lineHighlight, isPlaying)
                        }
                        val romanization = line.romanization
                        if (romanization != null && romanization.text.isNotBlank()) {
                            AdlibText(romanization, adlibPosition, position, offsetMs, isCurrent, alignment, lineHighlight, isPlaying)
                        }
                        val translation = line.translation
                        if (translation != null && translation.text.isNotBlank()) {
                            AdlibText(translation, adlibPosition, position, offsetMs, isCurrent, alignment, lineHighlight, isPlaying)
                        }
                    }
                    // After the part's last line, so the current-line anchor stays on the text.
                    if (endsSongPart(loadedLines, i)) Spacer(Modifier.height(AuraSpacing.lyricPartGap))
                }
                val credits = loadedLines.first().credits
                if (credits.writers.isNotEmpty() || credits.transcribers.isNotEmpty()) {
                    item { LyricCreditsFooter(credits) }
                }
                // Enough trailing scroll range for the final line to reach the anchor.
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
            val shape = AuraShapes.control
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

@Composable
internal fun LyricCreditsFooter(credits: LyricCredits) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(
            start = AuraSpacing.screenHorizontal,
            end = AuraSpacing.screenHorizontal,
            top = AuraSpacing.lg
        ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (credits.writers.isNotEmpty()) {
            Text(
                text = "Written by: ${credits.writers.joinToString()}",
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
        }
        if (credits.transcribers.isNotEmpty()) {
            Text(
                text = "Lyrics transcribed by: ${credits.transcribers.joinToString()}",
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
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
    webEnabled: Boolean,
    instrumental: Boolean
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
            if (instrumental) "Instrumental" else "No synced lyrics for this track",
            color = TextPrimary,
            fontSize = AuraTypography.title
        )
        Spacer(Modifier.height(AuraSpacing.xs))
        androidx.tv.material3.Text(
            when {
                instrumental -> "This track has no vocals."
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
    val artworkUrl = app.jellyfin.auth.session?.let {
        item.resolveArtwork()?.url(it.serverUrl, maxWidth = 240)
    }
    val shape = AuraShapes.card
    Row(
        Modifier
            .widthIn(max = AuraSizes.lyricCardWidth)
            .clip(shape)
            .background(AuraColors.CardScrim, shape)
            .padding(AuraSpacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (artworkUrl != null) {
            AsyncImage(
                model = artworkUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(AuraSizes.artworkChipLarge)
                    .clip(AuraShapes.card)
                    .background(SurfaceHigh)
            )
            Spacer(Modifier.width(AuraSpacing.md))
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

private sealed interface LyricRow {
    data class Line(val index: Int) : LyricRow
    data class Break(val gap: LyricBreak) : LyricRow
}

/** Lines in order, each break slotted in right after the line it follows. */
private fun lyricRows(lineCount: Int, breaks: List<LyricBreak>): List<LyricRow> {
    val after = breaks.associateBy { it.afterLine }
    return buildList {
        after[-1]?.let { add(LyricRow.Break(it)) }
        for (i in 0 until lineCount) {
            add(LyricRow.Line(i))
            after[i]?.let { add(LyricRow.Break(it)) }
        }
    }
}

/**
 * Three dots holding the current-line slot through an instrumental break.
 * They light up one by one as the break runs out and breathe while they wait;
 * the row collapses to nothing when no break is active.
 */
@Composable
private fun LyricBreakDots(
    visible: Boolean,
    progress: Float,
    alignment: Alignment.Horizontal,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier.fillMaxWidth(),
        enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
        exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()
    ) {
        // The position only ticks every 500 ms; easing across each tick keeps the fill continuous.
        val fill by animateFloatAsState(progress, tween(POSITION_TICK_MS, easing = LinearEasing), label = "breakFill")
        val breath by rememberInfiniteTransition(label = "breakBreath").animateFloat(
            initialValue = 1f,
            targetValue = AuraMotion.lyricBreakBreathScale,
            animationSpec = infiniteRepeatable(
                tween(AuraMotion.lyricBreakBreathMs, easing = FastOutSlowInEasing),
                RepeatMode.Reverse
            ),
            label = "breakScale"
        )
        Row(
            Modifier
                .fillMaxWidth()
                .height(AuraSizes.lyricLinePitch),
            horizontalArrangement = Arrangement.spacedBy(AuraSpacing.sm, alignment),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(BREAK_DOTS) { dot ->
                val lit = (fill * BREAK_DOTS - dot).coerceIn(0f, 1f)
                Box(
                    Modifier
                        .size(AuraSizes.lyricBreakDot)
                        .graphicsLayer {
                            scaleX = breath
                            scaleY = breath
                            alpha = AuraMotion.lyricBreakDimAlpha + (1f - AuraMotion.lyricBreakDimAlpha) * lit
                        }
                        .background(TextPrimary, CircleShape)
                )
            }
        }
    }
}

private const val BREAK_DOTS = 3
private const val POSITION_TICK_MS = 500

internal fun endsSongPart(lines: List<LyricLine>, index: Int): Boolean =
    index < lines.lastIndex && lines[index].part != lines[index + 1].part

fun computeCurrentLine(lines: List<LyricLine>?, positionMs: Long): Int {
    if (lines.isNullOrEmpty()) return -1
    if (!lines[0].isSynced) return -1
    var idx = -1
    for (i in lines.indices) {
        if (positionMs >= lines[i].startMs) idx = i else break
    }
    return idx
}

@Composable
private fun KaraokeLine(
    line: TimedLyricLine,
    positionMs: Long,
    nextLineStartMs: Long?,
    playing: Boolean,
    anchorMs: Long,
    accent: Color,
    alignment: TextAlign,
    fontSize: TextUnit = AuraTypography.headline,
    fontWeight: FontWeight? = FontWeight.SemiBold,
    modifier: Modifier = Modifier,
    fills: (Long) -> List<KaraokeFill> = { karaokeFills(line, it, nextLineStartMs) }
) {
    val elapsed = remember(line, positionMs, playing) { mutableLongStateOf(0L) }
    var layout by remember(line) { mutableStateOf<TextLayoutResult?>(null) }
    val clipPath = remember { Path() }
    val offsetBoxes = remember(layout, line) {
        val res = layout ?: return@remember emptyList<Pair<androidx.compose.ui.geometry.Rect, ResolvedTextDirection>>()
        val len = line.text.length
        List(len) { offset ->
            res.getBoundingBox(offset) to res.getBidiRunDirection(offset)
        }
    }
    LaunchedEffect(line, positionMs, playing, anchorMs) {
        elapsed.longValue = 0L
        if (playing) while (true) {
            withFrameMillis { elapsed.longValue = (SystemClock.uptimeMillis() - anchorMs).coerceIn(0L, 750L) }
        }
    }
    androidx.tv.material3.Text(
        line.text,
        color = TextSecondary,
        fontSize = fontSize,
        fontWeight = fontWeight,
        textAlign = alignment,
        onTextLayout = { layout = it },
        modifier = modifier.fillMaxWidth().drawWithContent {
            drawContent()
            val result = layout ?: return@drawWithContent
            clipPath.rewind()
            fills(positionMs + elapsed.longValue).forEach { fill ->
                val start = fill.start.coerceIn(0, offsetBoxes.size)
                val end = fill.end.coerceIn(start, offsetBoxes.size)
                val glyphs = offsetBoxes.subList(start, end).distinct()
                var remaining = glyphs.sumOf { it.first.width.toDouble() }.toFloat() * fill.fraction
                glyphs.forEach { (box, direction) ->
                    val width = remaining.coerceIn(0f, box.width.coerceAtLeast(0f))
                    if (width > 0f) {
                        clipPath.addRect(if (direction == ResolvedTextDirection.Rtl) box.copy(left = box.right - width)
                            else box.copy(right = box.left + width))
                    }
                    remaining -= box.width
                }
            }
            clipPath(clipPath) { drawText(result, color = accent) }
        }
    )
}

@Composable
private fun AdlibText(
    adlib: LyricAdlib,
    adlibPosition: State<Long?>,
    playbackPosition: State<Long>,
    offsetMs: Long,
    leadIsCurrent: Boolean,
    alignment: TextAlign,
    accent: Color,
    playing: Boolean
) {
    val modifier = Modifier.fillMaxWidth().padding(top = AuraSpacing.xxs)
    if (adlib.isTimed) {
        val positionMs = ((adlibPosition.value ?: playbackPosition.value) + offsetMs).coerceAtLeast(0L)
        val anchorMs = remember(positionMs, playing) { SystemClock.uptimeMillis() }
        KaraokeLine(TimedLyricLine(0L, adlib.text), positionMs, null, playing, anchorMs, accent, alignment,
            fontSize = AuraTypography.body, fontWeight = null, modifier = modifier,
            fills = { adlib.karaokeFills(it, leadIsCurrent) })
    } else {
        androidx.tv.material3.Text(
            adlib.text,
            color = TextSecondary,
            fontSize = AuraTypography.body,
            textAlign = alignment,
            modifier = modifier
        )
    }
}
