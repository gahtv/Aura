package com.hans.aura.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TtmlLyricsTest {
    private fun parse(body: String) = LyricsStore.parseLyricsText(
        """<?xml version="1.0"?><tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata"><body><div>$body</div></body></tt>""")

    @Test
    fun `timed spans preserve separation and omit background and translation`() {
        val line = parse("""<p begin="00:00:12.000" end="00:15"><span begin="12s">Hello</span> <span begin="00:12.500">world</span><span begin="13s" ttm:role="x-bg">echo</span><span ttm:role="x-translation">bonjour</span></p>""").single()
        assertEquals("Hello world", line.text)
        assertEquals(12000L, line.startMs)
        assertEquals(listOf(TimedWord(12000, "Hello"), TimedWord(12500, "world")), line.words)
        assertTrue(line.isSynced)
        assertEquals(listOf(KaraokeSpan("Hello", true), KaraokeSpan(" world", false)),
            karaokeSpans(TimedLyricLine(line.startMs, line.text, words = line.words), 12200))
    }

    @Test
    fun `clock and offset times and plain paragraphs`() {
        val lines = parse("""<p begin="01:02.345" end="65s">Clock</p><p begin="12.5s"><span begin="12.501">Offset</span></p><p begin="500ms">Milliseconds</p>""")
        assertEquals(listOf(500L, 12500L, 62345L), lines.map { it.startMs })
        assertEquals(12501L, lines[1].words.single().startMs)
        assertTrue(lines[0].words.isEmpty())
    }

    @Test
    fun `paragraphs are sorted stably by start time`() {
        val lines = parse("""<p begin="20s">Later</p><p begin="10s">First</p><p begin="10s">Second</p>""")
        assertEquals(listOf(10000L, 10000L, 20000L), lines.map { it.startMs })
        assertEquals(listOf("First", "Second", "Later"), lines.map { it.text })
    }

    @Test
    fun `span offsets below paragraph start become absolute word times`() {
        val line = parse("""<p begin="12s"><span><span begin="0s">Hello</span> <span begin="500ms">world</span></span></p>""").single()
        assertEquals(12000L, line.startMs)
        assertEquals(listOf(TimedWord(12000, "Hello"), TimedWord(12500, "world")), line.words)
    }

    @Test
    fun `span times stay absolute unless all are below paragraph start`() {
        val line = parse("""<p begin="12s"><span><span begin="11s">Hello</span> <span begin="12.5s">world</span></span></p>""").single()
        assertEquals(listOf(TimedWord(11000, "Hello"), TimedWord(12500, "world")), line.words)
        assertTrue(parse("""<p begin="12s">No spans</p>""").single().words.isEmpty())
    }

    @Test
    fun `untimed ttml is plain and malformed ttml is empty`() {
        val line = parse("<p>Plain <span>words</span></p>").single()
        assertEquals("Plain words", line.text)
        assertFalse(line.isSynced)
        assertTrue(line.words.isEmpty())
        assertTrue(LyricsStore.parseLyricsText("<tt><body><p>broken").isEmpty())
        assertTrue(parse("<p> </p>").isEmpty())
    }
}
