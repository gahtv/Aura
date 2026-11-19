package com.hans.aura.data

/**
 * A stretch of a song where nothing is sung: the intro before the first line
 * ([afterLine] = -1), or the gap between the end of line [afterLine] and the
 * start of the next one.
 */
data class LyricBreak(
    val afterLine: Int,
    val startMs: Long,
    val endMs: Long
) {
    operator fun contains(positionMs: Long): Boolean = positionMs >= startMs && positionMs < endMs

    /** How far through the break [positionMs] is, from 0 to 1. */
    fun progress(positionMs: Long): Float =
        ((positionMs - startMs).toFloat() / (endMs - startMs)).coerceIn(0f, 1f)
}

/** Shorter silences read as phrasing, not a break worth marking. */
const val MIN_LYRIC_BREAK_MS = 4_000L

/**
 * The breaks in synced [lines] lasting at least [minGapMs].
 *
 * A gap only counts once every earlier line is known to have ended: a line
 * without an end time is taken to run until the next one starts, so sources
 * that never time line ends produce no breaks beyond the intro.
 */
fun lyricBreaks(lines: List<LyricLine>, minGapMs: Long = MIN_LYRIC_BREAK_MS): List<LyricBreak> {
    if (lines.isEmpty() || !lines[0].isSynced) return emptyList()
    val breaks = mutableListOf<LyricBreak>()
    if (lines[0].startMs >= minGapMs) breaks += LyricBreak(-1, 0L, lines[0].startMs)
    var sungUntil = Long.MIN_VALUE
    for (i in 0 until lines.lastIndex) {
        val next = lines[i + 1]
        sungUntil = maxOf(sungUntil, lines[i].sungUntil() ?: next.startMs)
        if (next.startMs - sungUntil >= minGapMs) breaks += LyricBreak(i, sungUntil, next.startMs)
    }
    return breaks
}

/** An adlib trailing past its lead line keeps the break from starting; an open-ended timed one runs to the next line. */
private fun LyricLine.sungUntil(): Long? {
    val end = endMs ?: return null
    val adlib = adlib ?: return end
    return adlib.endMs?.let { maxOf(end, it) } ?: end.takeUnless { adlib.isTimed }
}
