package com.hans.aura.ui.nowplaying

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.structuralEqualityPolicy
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
import com.hans.aura.model.Track
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
import com.hans.aura.data.effectiveEndMs
import com.hans.aura.data.karaokeFills
import com.hans.aura.data.firstCueMs
import com.hans.aura.data.lyricBreaks
import com.hans.aura.data.toLyricLine
import com.hans.aura.data.toTimedLyricLine
import com.hans.aura.ui.common.AutoFitMarqueeText
import com.hans.aura.ui.common.AuraLoadingIndicator
import com.hans.aura.ui.common.url
import com.hans.aura.ui.player.PlayerConnection
import com.hans.aura.ui.player.rememberPlaybackPosition
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

internal fun lyricVoiceStyle(
    voice: LyricVoice?,
    multipleVoices: Boolean,
    colors: androidx.tv.material3.ColorScheme
): Pair<TextAlign, Color> {
    if (!multipleVoices) return TextAlign.Center to colors.primary
    return when (voice?.kind) {
        LyricVoice.Kind.PRIMARY -> TextAlign.Start to colors.primary
        LyricVoice.Kind.SECONDARY -> TextAlign.End to colors.tertiary
        else -> TextAlign.Center to colors.primary
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
 *
 * In [ambient] mode (the Now Playing HUD hidden) all of those states collapse
 * to nothing: the pane only ever draws actual lyric lines.
 */
@Composable
fun LyricsPane(
    app: AuraApp,
    player: PlayerConnection,
    onUserInteraction: () -> Unit = {},
    // Ambient variant (right-hand pane while the Now Playing HUD is hidden):
    // renders nothing at all until real lyrics exist — no loading spinner, no
    // empty-state card — so lyric-less tracks keep that side of the screen
    // clear instead of duplicating the info under the cover.
    ambient: Boolean = false,
    // Companion signal for the ambient pane: whether the current track's lyrics
    // resolved to anything. Never called while loading — the host keeps its
    // previous layout until it knows for sure.
    onAvailability: ((Boolean) -> Unit)? = null
) {
    val colors = MaterialTheme.colorScheme
    val item by player.current.collectAsState()
    val playback = rememberPlaybackPosition(player)
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

    val offset = remember(item?.id) { mutableStateOf(Prefs.lyricsOffsetMs(trackId)) }
    var offsetMs by offset
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
        val local = app.container.media.preferredLyrics(cur.id)
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

    // Tell the ambient host whether this track really has lyrics. Loading
    // (lines == null) stays silent so the layout does not flip back and forth
    // while a fetch is in flight.
    LaunchedEffect(lines) {
        val loaded = lines ?: return@LaunchedEffect
        onAvailability?.invoke(loaded.isNotEmpty())
    }

    // +offset = the highlighted line is one step EARLIER than the audio clock.
    val effectivePosition: () -> Long = remember(playback, offset) {
        { (playback.value + offset.value).coerceAtLeast(0L) }
    }
    val breaks = remember(lines) { lyricBreaks(lines.orEmpty()) }
    val cursor by rememberLyricCursor(lines, breaks, effectivePosition)
    val (currentLineIndex, activeBreak, overlappingLines, pendingLine) = cursor
    val rows = remember(lines, breaks) { lyricRows(lines.orEmpty().size, breaks) }
    // During a break the dots take the current line's anchor slot.
    val followRow = activeBreak?.let { rows.indexOf(LyricRow.Break(it)) }
        ?: pendingLine?.let { rows.indexOf(LyricRow.Line(it)) }
        ?: rows.indexOf(LyricRow.Line(currentLineIndex.coerceAtLeast(0)))
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
            loadedLines == null -> if (!ambient) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    AuraLoadingIndicator("Loading lyrics")
                }
            }
            loadedLines.isEmpty() -> if (!ambient) {
                NoLyricsState(
                    app = app,
                    item = item,
                    webChecked = webChecked,
                    webFound = webFound,
                    webEnabled = Prefs.lyricsWebFallback,
                    instrumental = instrumental
                )
            }
            else -> LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    // Ambient is a view-only display: any key wakes the HUD at
                    // the screen root before it can reach this pane, and a
                    // non-focusable list keeps a fade-out window from trapping
                    // focus on a node that is about to disappear.
                    .focusable(enabled = !ambient),
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
                        val (alignment, _) = lyricVoiceStyle(next?.voice, multipleVoices, colors)
                        LyricBreakDots(
                            visible = row.gap == activeBreak,
                            progress = { row.gap.progress(effectivePosition()) },
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
                    val (alignment, lineHighlight) = lyricVoiceStyle(line.voice, multipleVoices, colors)
                    val secondaryVoice = multipleVoices && line.voice?.kind == LyricVoice.Kind.SECONDARY
                    // The line that just ended rests with the others through a break.
                    val isCurrent = i == currentLineIndex && activeBreak == null && pendingLine == null
                    // A sub-line cued before its lead (after a break or the intro) runs on as if its lead were current.
                    val subLeadIsCurrent = isCurrent || i == pendingLine
                    // An earlier line that is still being sung (its own end time
                    // has not passed) keeps its highlight at the resting size,
                    // while the current line alone stays the followed anchor.
                    val isOverlapping = !isCurrent && i in overlappingLines && activeBreak == null
                    val isActive = isCurrent || isOverlapping
                    // Line-only timestamps do not carry karaoke word timing.
                    val timed = timedLines?.getOrNull(i)?.takeIf { isActive && it.words.isNotEmpty() }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            // Only the followed line gets the voice band, so the
                            // anchor stays visually dominant over overlaps.
                            .then(if (multipleVoices && isCurrent) {
                                Modifier.background(colors.secondaryContainer)
                            } else Modifier)
                            // Ambient is view-only: a line must not be a
                            // focus/click target there, or a wake fade leaves a
                            // trap on a node that is about to decompose.
                            .clickable(enabled = line.isSynced && !ambient) {
                                player.seekTo(line.startMs)
                                followPaused = false
                                onUserInteraction()
                            }
                            .padding(vertical = AuraSpacing.lyricLineVertical)
                            .then(if (multipleVoices) Modifier.padding(horizontal = AuraSpacing.screenHorizontal) else Modifier)
                    ) {
                        if (timed != null) {
                            KaraokeLine(timed, effectivePosition,
                                loadedLines.getOrNull(i + 1)?.startMs, alignment, secondaryVoice = secondaryVoice,
                                fontSize = if (isCurrent) AuraTypography.headline else AuraTypography.title,
                                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal)
                        } else {
                            androidx.tv.material3.Text(
                                AnnotatedString(line.text.ifBlank { "\u00A0" }),
                                color = if (isActive) lineHighlight else TextPrimary,
                                fontSize = if (isCurrent) AuraTypography.headline else AuraTypography.title,
                                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                textAlign = alignment,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        val adlib = line.adlib
                        if (adlib != null && adlib.text.isNotBlank()) {
                            AdlibText(adlib, effectivePosition, subLeadIsCurrent, isOverlapping, alignment, secondaryVoice)
                        }
                        val romanization = line.romanization
                        if (romanization != null && romanization.text.isNotBlank()) {
                            AdlibText(romanization, effectivePosition, subLeadIsCurrent, isOverlapping, alignment, secondaryVoice)
                        }
                        val translation = line.translation
                        if (translation != null && translation.text.isNotBlank()) {
                            AdlibText(translation, effectivePosition, subLeadIsCurrent, isOverlapping, alignment, secondaryVoice)
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
                color = colors.onPrimary,
                fontSize = AuraTypography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(shape)
                    .background(colors.primary)
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
    item: Track?,
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
            LyricsTrackCard(app = app, item = item)
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
private fun LyricsTrackCard(app: AuraApp, item: Track) {
    val colors = MaterialTheme.colorScheme
    val artworkUrl = app.jellyfin.auth.session?.let {
        item.artwork?.url(it.serverUrl, maxWidth = 240)
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
                    color = colors.primary,
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

internal data class LyricCursor(
    val lineIndex: Int,
    val activeBreak: LyricBreak?,
    // Earlier lines (any of them, not just the previous one) whose sung window
    // still covers the playback position; they stay word-by-word highlighted.
    val overlappingLines: Set<Int> = emptySet(),
    // The next line, once a sub-line cued before its lead ends the intro or a break: it is
    // followed without highlighting either the ended lead or the not-yet-started one.
    val pendingLine: Int? = null
)

@Composable
internal fun rememberLyricCursor(
    lines: List<LyricLine>?,
    breaks: List<LyricBreak>,
    effectivePosition: () -> Long
): State<LyricCursor> = remember(lines, breaks, effectivePosition) {
    val overlapEnds = lineOverlapEnds(lines)
    derivedStateOf(structuralEqualityPolicy()) {
        val position = effectivePosition()
        val activeBreak = breaks.firstOrNull { position in it }
        val lineIndex = computeCurrentLine(lines, position)
        // A break owns the clock: nothing else highlights while the dots run.
        val overlapping = if (activeBreak == null) computeOverlappingLines(lines, position, lineIndex, overlapEnds) else emptySet()
        val next = lineIndex + 1
        val pending = next.takeIf {
            activeBreak == null && lines != null && next <= lines.lastIndex && lines[0].isSynced &&
                position >= lines[next].firstCueMs() &&
                (lineIndex == -1 || breaks.any { it.afterLine == lineIndex })
        }
        LyricCursor(lineIndex, activeBreak, overlapping, pending)
    }
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
    progress: () -> Float,
    alignment: Alignment.Horizontal,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier.fillMaxWidth(),
        enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
        exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()
    ) {
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
                Box(
                    Modifier
                        .size(AuraSizes.lyricBreakDot)
                        .graphicsLayer {
                            scaleX = breath
                            scaleY = breath
                            val lit = (progress() * BREAK_DOTS - dot).coerceIn(0f, 1f)
                            alpha = AuraMotion.lyricBreakDimAlpha + (1f - AuraMotion.lyricBreakDimAlpha) * lit
                        }
                        .background(TextPrimary, CircleShape)
                )
            }
        }
    }
}

private const val BREAK_DOTS = 3

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

/** Per-line [effectiveEndMs], or [Long.MIN_VALUE] when open-ended; computed once per lyrics load. */
internal fun lineOverlapEnds(lines: List<LyricLine>?): LongArray =
    LongArray(lines?.size ?: 0) { lines!![it].effectiveEndMs() ?: Long.MIN_VALUE }

/**
 * Earlier lines that are STILL being sung at [positionMs]: anything before the
 * current line whose explicit end (line end or latest word end) has not passed.
 * Scans every earlier index, so two, three or more previous lines can overlap
 * at once, on or off screen; open-ended lines are treated as finished when the
 * next line starts. Returns the shared empty set when nothing overlaps, so the
 * common no-overlap playback path allocates nothing.
 */
internal fun computeOverlappingLines(
    lines: List<LyricLine>?,
    positionMs: Long,
    currentLineIndex: Int = computeCurrentLine(lines, positionMs),
    ends: LongArray = lineOverlapEnds(lines)
): Set<Int> {
    if (lines.isNullOrEmpty() || !lines[0].isSynced || currentLineIndex <= 0) return emptySet()
    var overlapping: MutableSet<Int>? = null
    for (i in 0 until minOf(currentLineIndex, ends.size)) {
        if (positionMs >= lines[i].startMs && positionMs < ends[i]) {
            if (overlapping == null) overlapping = mutableSetOf()
            overlapping.add(i)
        }
    }
    return overlapping ?: emptySet()
}

@Composable
private fun KaraokeLine(
    line: TimedLyricLine,
    position: () -> Long,
    nextLineStartMs: Long?,
    alignment: TextAlign,
    secondaryVoice: Boolean = false,
    fontSize: TextUnit = AuraTypography.headline,
    fontWeight: FontWeight? = FontWeight.SemiBold,
    modifier: Modifier = Modifier,
    fills: (Long) -> List<KaraokeFill> = { karaokeFills(line, it, nextLineStartMs) }
) {
    val colors = MaterialTheme.colorScheme
    var layout by remember(line) { mutableStateOf<TextLayoutResult?>(null) }
    val clipPath = remember { Path() }
    // Glyph boxes come from the exact layout being drawn, so a line stepping
    // between the headline and resting sizes never clips with stale boxes.
    val glyphBoxes = remember(line) { GlyphBoxes() }
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
            val offsetBoxes = glyphBoxes.of(result)
            clipPath.rewind()
            fills(position()).forEach { fill ->
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
            clipPath(clipPath) { drawText(result, color = if (secondaryVoice) colors.tertiary else colors.primary) }
        }
    )
}

/** Per-offset glyph boxes of the last [TextLayoutResult] drawn, recomputed only when the layout changes. */
private class GlyphBoxes {
    private var source: TextLayoutResult? = null
    private var boxes: List<Pair<androidx.compose.ui.geometry.Rect, ResolvedTextDirection>> = emptyList()

    fun of(layout: TextLayoutResult): List<Pair<androidx.compose.ui.geometry.Rect, ResolvedTextDirection>> {
        if (layout !== source) {
            source = layout
            boxes = List(layout.layoutInput.text.length) { offset ->
                layout.getBoundingBox(offset) to layout.getBidiRunDirection(offset)
            }
        }
        return boxes
    }
}

@Composable
private fun AdlibText(
    adlib: LyricAdlib,
    position: () -> Long,
    leadIsCurrent: Boolean,
    leadIsOverlapping: Boolean,
    alignment: TextAlign,
    secondaryVoice: Boolean
) {
    val modifier = Modifier.fillMaxWidth().padding(top = AuraSpacing.xxs)
    if (adlib.isTimed) {
        KaraokeLine(TimedLyricLine(0L, adlib.text), position, null, alignment, secondaryVoice,
            fontSize = AuraTypography.body, fontWeight = null, modifier = modifier,
            fills = { adlib.karaokeFills(it, leadIsCurrent, leadIsOverlapping) })
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
