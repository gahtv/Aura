package com.hans.aura.ui.nowplaying

import com.hans.aura.data.LyricLine
import com.hans.aura.data.LyricsStore
import com.hans.aura.data.TimedWord
import org.junit.Assert.assertEquals
import org.junit.Test

class CurrentLyricLineTest {
    @Test
    fun `unsynced lyrics have no current line`() {
        val plain = LyricsStore.parseLrcText("First line\nSecond line")
        assertEquals(-1, computeCurrentLine(plain, 0L))
        assertEquals(-1, computeCurrentLine(plain, 90_000L))
    }

    @Test
    fun `synced lyrics follow the clock`() {
        val synced = LyricsStore.parseLrcText("[00:05]First\n[00:10]Second")
        assertEquals(-1, computeCurrentLine(synced, 1_000L))
        assertEquals(0, computeCurrentLine(synced, 5_000L))
        assertEquals(1, computeCurrentLine(synced, 12_000L))
    }

    private fun line(startMs: Long, endMs: Long?, words: List<TimedWord> = emptyList()) =
        LyricLine(startMs, "Line", isSynced = true, words = words, endMs = endMs)

    @Test
    fun `single previous line overlaps until its own end`() {
        // Line 0 is held well past line 1's start.
        val lines = listOf(line(0, 12_000), line(10_000, 20_000))
        assertEquals(setOf(0), computeOverlappingLines(lines, 11_000))
        // Exactly at the end the overlap is over.
        assertEquals(emptySet<Int>(), computeOverlappingLines(lines, 12_000))
        // Before line 1 starts there is no "previous" line yet.
        assertEquals(emptySet<Int>(), computeOverlappingLines(lines, 5_000))
    }

    @Test
    fun `several previous lines overlap and drop out one by one`() {
        val lines = listOf(
            line(0, 30_000),
            line(10_000, 25_000),
            line(20_000, 22_000),
            line(21_000, null)
        )
        // All three earlier lines are still being sung at once.
        assertEquals(setOf(0, 1, 2), computeOverlappingLines(lines, 21_500))
        assertEquals(setOf(0, 1), computeOverlappingLines(lines, 22_000))
        assertEquals(setOf(0), computeOverlappingLines(lines, 25_000))
        assertEquals(emptySet<Int>(), computeOverlappingLines(lines, 30_000))
    }

    @Test
    fun `simultaneous duet start keeps the earlier voice highlighted`() {
        val lines = listOf(line(10_000, 16_000), line(10_000, 13_000))
        // The later index is current; the earlier one keeps its highlight.
        assertEquals(setOf(0), computeOverlappingLines(lines, 11_000))
        assertEquals(setOf(0), computeOverlappingLines(lines, 13_000))
        assertEquals(emptySet<Int>(), computeOverlappingLines(lines, 16_000))
    }

    @Test
    fun `open-ended lines never overlap`() {
        val open = listOf(line(0, null), line(5_000, null))
        assertEquals(emptySet<Int>(), computeOverlappingLines(open, 7_000))
        // A word end counts as an explicit end: this line does overlap...
        val held = listOf(
            line(0, null),
            line(10_000, null),
            line(20_000, null, words = listOf(TimedWord(20_000, "Held", 26_000))),
            line(21_000, null)
        )
        assertEquals(setOf(2), computeOverlappingLines(held, 25_000))
        assertEquals(emptySet<Int>(), computeOverlappingLines(held, 26_000))
    }

    @Test
    fun `unsynced lyrics have no overlapping lines`() {
        val plain = LyricsStore.parseLrcText("First line\nSecond line")
        assertEquals(emptySet<Int>(), computeOverlappingLines(plain, 0L))
        assertEquals(emptySet<Int>(), computeOverlappingLines(plain, 90_000L))
        assertEquals(emptySet<Int>(), computeOverlappingLines(null, 5_000L))
    }
}
