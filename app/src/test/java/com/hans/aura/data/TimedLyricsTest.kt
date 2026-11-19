package com.hans.aura.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TimedLyricsTest {
    @Test
    fun `intra word lrc stamps have independent syllable ranges and fractions`() {
        val line = LyricsStore.parseLrcText("[00:01]<00:01.00>Hol<00:01.40>ding <00:02.00>on <00:02.50>").single().toTimedLyricLine()
        assertEquals("Holding on", line.text)
        assertEquals(listOf(KaraokeFill(0, 3, 0.5f), KaraokeFill(3, 7, 0f), KaraokeFill(8, 10, 0f)), karaokeFills(line, 1200))
        assertEquals(listOf(KaraokeFill(0, 3, 1f), KaraokeFill(3, 7, 0.5f), KaraokeFill(8, 10, 0f)), karaokeFills(line, 1700))
        assertEquals(listOf(KaraokeFill(0, 3, 1f), KaraokeFill(3, 7, 1f), KaraokeFill(8, 10, 0.5f)), karaokeFills(line, 2250))
    }

    @Test
    fun `adlibs preserve untimed text and empty input while timed segments fill independently`() {
        assertEquals(emptyList<KaraokeFill>(), LyricAdlib(emptyList()).karaokeFills(1500, true))
        val adlib = LyricAdlib(listOf(AdlibSegment("(again) "), AdlibSegment("Hol", 1000), AdlibSegment("ding", 2000)), 3000)
        assertEquals(listOf(KaraokeFill(0, 8, 0f), KaraokeFill(8, 11, 0.5f), KaraokeFill(11, 15, 0f)), adlib.karaokeFills(1500, false))
        assertEquals(listOf(KaraokeFill(0, 8, 0f), KaraokeFill(8, 11, 0f), KaraokeFill(11, 15, 0f)), adlib.karaokeFills(3000, false))
        assertEquals(listOf(KaraokeFill(0, 8, 0f), KaraokeFill(8, 11, 1f), KaraokeFill(11, 15, 1f)), adlib.karaokeFills(3000, true))
    }

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
    fun `lrc derives next word then earlier of next line and one second cap`() {
        val parsed = LyricsStore.parseLrcText("[00:01]<00:01>Hello <00:03>world").single()
        val line = TimedLyricLine(parsed.startMs, parsed.text, words = parsed.words)
        assertEquals(listOf(KaraokeFill(0, 5, 0.5f), KaraokeFill(6, 11, 0f)), karaokeFills(line, 2000))
        assertEquals(1f, karaokeFills(line, 4000, 5000).last().fraction)
        assertEquals(0.5f, karaokeFills(line, 3250, 3500).last().fraction)
        assertEquals(0.5f, karaokeFills(line, 3500, 100000).last().fraction)
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
    fun `trailing lrc stamp preserves a long held final word`() {
        val parsed = LyricsStore.parseLrcText("[00:01]<00:01>Hold <00:02>on <00:05>  ").single()
        val line = TimedLyricLine(parsed.startMs, parsed.text, words = parsed.words)
        assertEquals(5000L, line.words.last().endMs)
        assertEquals(0.5f, karaokeFills(line, 3500, 10000).last().fraction)
        assertEquals(1f, karaokeFills(line, 5000, 10000).last().fraction)
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
