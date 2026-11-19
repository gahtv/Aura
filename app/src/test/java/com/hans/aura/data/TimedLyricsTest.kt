package com.hans.aura.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TimedLyricsTest {
    @Test
    fun `fraction clamps and zero or reversed durations snap`() {
        val word = TimedWord(1000, "Hello")
        assertEquals(0f, wordFillFraction(word, 2000, 999))
        assertEquals(0f, wordFillFraction(word, 2000, 1000))
        assertEquals(0.5f, wordFillFraction(word, 2000, 1500))
        assertEquals(1f, wordFillFraction(word, 2000, 3000))
        assertEquals(1f, wordFillFraction(word, 1000, 1000))
        assertEquals(1f, wordFillFraction(word, 900, 1000))
    }

    @Test
    fun `lrc derives next word then next line then one second cap`() {
        val parsed = LyricsStore.parseLrcText("[00:01]<00:01>Hello <00:03>world").single()
        val line = TimedLyricLine(parsed.startMs, parsed.text, words = parsed.words)
        assertEquals(listOf(KaraokeFill(0, 5, 0.5f), KaraokeFill(6, 11, 0f)), karaokeFills(line, 2000))
        assertEquals(0.5f, karaokeFills(line, 4000, 5000).last().fraction)
        assertEquals(0.5f, karaokeFills(line, 3500).last().fraction)
        assertEquals(1f, karaokeFills(line, 5000).last().fraction)
    }

    @Test
    fun `explicit end wins and original whitespace is preserved`() {
        val line = TimedLyricLine(1000, " Hello  world! ", words = listOf(
            TimedWord(1000, "Hello", 5000), TimedWord(2000, "world!")))
        assertEquals(KaraokeFill(1, 6, 0.25f), karaokeFills(line, 2000).first())
    }

    @Test
    fun `empty and mismatched words use whole line fallback`() {
        val line = TimedLyricLine(1000, "Original")
        assertEquals(listOf(KaraokeFill(0, 8, 0f)), karaokeFills(line, 999))
        assertEquals(listOf(KaraokeFill(0, 8, 1f)), karaokeFills(line, 1000))
        assertEquals(listOf(KaraokeFill(0, 8, 1f)), karaokeFills(line.copy(words = listOf(TimedWord(500, "Other"))), 500))
        assertEquals(listOf(KaraokeFill(0, 0, 0f)), karaokeFills(line.copy(text = ""), 0))
    }
}
