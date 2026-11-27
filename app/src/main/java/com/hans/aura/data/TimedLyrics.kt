package com.hans.aura.data

/**
 * One word inside a word-timed lyric line.
 *
 * [startMs] and optional source-provided [endMs] are on the playback clock.
 */
data class TimedWord(
    val startMs: Long,
    val text: String,
    val endMs: Long? = null
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
    val translation: LyricAdlib? = null,
    val romanization: LyricAdlib? = null,
    val words: List<TimedWord> = emptyList(),
    val voice: LyricVoice? = null,
    val adlib: LyricAdlib? = null,
    val credits: LyricCredits = LyricCredits(),
    val part: Int = 0,
    val endMs: Long? = null
)

internal fun LyricLine.toTimedLyricLine() =
    TimedLyricLine(
        startMs, text, translation = translation, romanization = romanization, words = words, voice = voice,
        adlib = adlib, credits = credits, part = part, endMs = endMs
    )

internal fun TimedLyricLine.toLyricLine() =
    LyricLine(
        startMs, text, isSynced = true, words = words, voice = voice, adlib = adlib, credits = credits,
        part = part, translation = translation, romanization = romanization, endMs = endMs
    )

/** Exact display text (including spacing), optionally timed to the playback clock. */
/** The latest end of any word, known only when the last word has one; overlapping words may end out of order. */
internal fun List<TimedWord>.sungEnd(): Long? =
    lastOrNull()?.endMs?.let { last -> maxOf(last, maxOf { it.endMs ?: last }) }

/** Known end time of a line from its explicit [LyricLine.endMs] or latest word end, or null if open-ended. */
internal fun LyricLine.effectiveEndMs(): Long? =
    listOfNotNull(endMs, words.sungEnd()).maxOrNull()

data class AdlibSegment(val text: String, val startMs: Long? = null, val endMs: Long? = null)

/** Any lyric sub-line (adlib, translation or romanization), with optional independent timing. */
data class LyricAdlib(val segments: List<AdlibSegment>, val endMs: Long? = null) {
    val text: String = segments.joinToString("") { it.text }
    private val startMs = segments.mapNotNull { it.startMs }.minOrNull()
    val isTimed: Boolean get() = startMs != null

    internal fun karaokeFills(positionMs: Long, leadIsCurrent: Boolean): List<KaraokeFill> {
        val overlapping = endMs != null && startMs != null && positionMs in startMs until endMs
        val active = leadIsCurrent || overlapping
        var offset = 0
        return segments.flatMap { segment ->
            val start = segment.startMs
            val fills = if (active && start != null) {
                val next = segments.mapNotNull { it.startMs }.filter { it > start }.minOrNull()
                karaokeFills(TimedLyricLine(start, segment.text,
                    words = listOf(TimedWord(start, segment.text, segment.endMs ?: next))), positionMs, endMs)
            } else listOf(KaraokeFill(0, segment.text.length, 0f))
            fills.map { it.copy(start = it.start + offset, end = it.end + offset) }
                .also { offset += segment.text.length }
        }
    }
}

data class KaraokeFill(val start: Int, val end: Int, val fraction: Float)

fun wordFillFraction(word: TimedWord, endMs: Long, positionMs: Long): Float = when {
    positionMs < word.startMs -> 0f
    endMs <= word.startMs -> 1f
    else -> ((positionMs.toDouble() - word.startMs) / (endMs.toDouble() - word.startMs)).toFloat().coerceIn(0f, 1f)
}

/**
 * Ends use explicit word end, then next word start, then the line's own end, then the earlier of
 * next line start and a one-second cap. A next line start at or before the word start is ignored.
 */
fun karaokeFills(line: TimedLyricLine, positionMs: Long, nextLineStartMs: Long? = null): List<KaraokeFill> {
    val words = line.words.filter { it.text.isNotBlank() }
    fun fallback() = listOf(KaraokeFill(0, line.text.length,
        if (positionMs >= (words.firstOrNull()?.startMs ?: line.startMs)) 1f else 0f))
    if (words.isEmpty()) return fallback()
    val offsets = line.text.indices.filter { !line.text[it].isWhitespace() }
    val rebuilt = words.joinToString("") { it.text }.filterNot { it.isWhitespace() }
    if (!rebuilt.equals(offsets.map { line.text[it] }.joinToString(""), ignoreCase = true)) return fallback()
    var cursor = 0
    return words.mapIndexed { index, word ->
        val length = word.text.count { !it.isWhitespace() }
        val start = offsets[cursor]
        cursor += length
        val cap = word.startMs + 1000L
        val lineEnd = line.endMs?.takeIf { it > word.startMs }
        val nextStart = nextLineStartMs?.takeIf { it > word.startMs }
        val end = word.endMs ?: words.getOrNull(index + 1)?.startMs ?: lineEnd ?: minOf(nextStart ?: cap, cap)
        KaraokeFill(start, offsets[cursor - 1] + 1, wordFillFraction(word, end, positionMs))
    }
}
