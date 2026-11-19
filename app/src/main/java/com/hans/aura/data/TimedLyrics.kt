package com.hans.aura.data

/**
 * One word inside a word-timed lyric line.
 *
 * [startMs] is on the playback clock, so the karaoke highlight is a pure
 * comparison against the position the player is already publishing — no extra
 * clock, no animation driver.
 */
data class TimedWord(
    val startMs: Long,
    val text: String
)

/**
 * A lyric line a backend was able to time down to individual words.
 *
 * Neutral on purpose: this is not a backend-specific JSON DTO and not the LRC parser's
 * [LyricLine] — an implementation maps its own payload onto this shape, and a
 * source that cannot time words simply never produces one (see
 * [MusicSource.lyricsTimed]).
 *
 * [adlib] is optional background-vocal text with independent timing.
 */
data class TimedLyricLine(
    val startMs: Long,
    val text: String,
    val translation: String? = null,
    val words: List<TimedWord> = emptyList(),
    val voice: LyricVoice? = null,
    val adlib: LyricAdlib? = null,
    val credits: LyricCredits = LyricCredits(),
    val part: Int = 0,
    val endMs: Long? = null
)

internal fun LyricLine.toTimedLyricLine() =
    TimedLyricLine(startMs, text, words = words, voice = voice, adlib = adlib, credits = credits, part = part, endMs = endMs)

internal fun TimedLyricLine.toLyricLine() =
    LyricLine(
        startMs, text, isSynced = true, words = words, voice = voice, adlib = adlib, credits = credits,
        part = part, endMs = endMs
    )

/** A run of a lyric line, [sung] once the audio clock has passed its first word. */
data class KaraokeSpan(
    val text: String,
    val sung: Boolean
)

/** Exact display text (including spacing), optionally timed to the playback clock. */
data class AdlibSegment(val text: String, val startMs: Long? = null)

data class LyricAdlib(val segments: List<AdlibSegment>, val endMs: Long? = null) {
    val text: String = segments.joinToString("") { it.text }
    private val startMs = segments.mapNotNull { it.startMs }.minOrNull()
    val isTimed: Boolean get() = startMs != null

    internal fun karaokeSpans(positionMs: Long, leadIsCurrent: Boolean): List<KaraokeSpan> {
        val overlapping = endMs != null && startMs != null && positionMs in startMs until endMs
        val active = leadIsCurrent || overlapping
        return segments.map { segment ->
            KaraokeSpan(segment.text, active && segment.startMs?.let { positionMs >= it } == true)
        }
    }
}

/**
 * Splits [line] into runs that share a highlight state at [positionMs].
 *
 * The words are re-joined with whatever separator reproduces the line's own
 * text (word tokens often carry no spaces at all, or already carry their own),
 * and when they still cannot reconstruct the line the whole line is returned as
 * a single run — so a line always renders exactly the text the backend gave us,
 * just with the sung part of it marked.
 */
fun karaokeSpans(line: TimedLyricLine, positionMs: Long): List<KaraokeSpan> {
    val words = line.words.filter { it.text.isNotBlank() }
    if (words.isEmpty()) return listOf(KaraokeSpan(line.text, positionMs >= line.startMs))

    val tight = words.joinToString("") { it.text }
    val spaced = words.joinToString(" ") { it.text }
    // Pick the separator that reproduces the backend's line text: tokens often
    // carry no spaces at all, and some carry their own trailing one. Exact match
    // first, then a whitespace-insensitive one (punctuation/extra spaces differ).
    val separator = when {
        spaced.trim() == line.text.trim() -> " "
        tight.trim() == line.text.trim() -> ""
        sameText(spaced, line.text) -> " "
        sameText(tight, line.text) -> ""
        // Words that do not rebuild the line (different punctuation, a partial
        // transcription): keep the backend's line text, mark it as a whole.
        else -> return listOf(KaraokeSpan(line.text, positionMs >= words.first().startMs))
    }

    val spans = ArrayList<KaraokeSpan>(words.size)
    words.forEachIndexed { i, word ->
        val sung = positionMs >= word.startMs
        val piece = if (i == 0) word.text else separator + word.text
        val last = spans.lastOrNull()
        if (last != null && last.sung == sung) {
            spans[spans.lastIndex] = last.copy(text = last.text + piece)
        } else {
            spans += KaraokeSpan(piece, sung)
        }
    }
    return spans
}

/** Whitespace-insensitive comparison: tokens may or may not carry spaces. */
private fun sameText(a: String, b: String): Boolean =
    a.filterNot { it.isWhitespace() }.equals(b.filterNot { it.isWhitespace() }, ignoreCase = true)
