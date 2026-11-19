package com.hans.aura.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TtmlLyricsTest {
    @Test
    fun `word end and duration survive absolute and relative timing`() {
        val lines = parse("""<p begin="1s"><span begin="1s" end="2s" dur="5s">End</span> <span begin="2s" dur="500ms">Duration</span></p>
            <p begin="10s"><span begin="0s" end="500ms">Relative</span> <span begin="500ms" dur="250ms">duration</span></p>""")
        assertEquals(listOf(TimedWord(1000, "End", 2000), TimedWord(2000, "Duration", 2500)), lines[0].words)
        assertEquals(listOf(TimedWord(10000, "Relative", 10500), TimedWord(10500, "duration", 10750)), lines[1].words)
    }

    @Test
    fun `agents retain ids with first use ordering and group types`() {
        val lines = LyricsStore.parseLyricsText("""<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata><ttm:agent xml:id="bob" type="character"/><ttm:agent xml:id="choir" type="group"/><ttm:agent xml:id="alice" type="person"/><ttm:agent xml:id="chris" type="person"/></metadata></head>
            <body><div><p begin="0s" ttm:agent="alice">First</p><p begin="1s" ttm:agent="choir">Group</p>
            <p begin="2s" ttm:agent="bob">Second</p><p begin="3s" ttm:agent="chris">Third</p>
            <p begin="4s" ttm:agent="alice bob">Together</p><p begin="5s">No voice</p></div></body></tt>""")
        assertEquals(listOf(
            LyricVoice("alice", LyricVoice.Kind.PRIMARY), LyricVoice("choir", LyricVoice.Kind.GROUP),
            LyricVoice("bob", LyricVoice.Kind.SECONDARY), LyricVoice("chris", LyricVoice.Kind.PRIMARY),
            LyricVoice("alice bob", LyricVoice.Kind.GROUP), null
        ), lines.map { it.voice })
    }

    @Test
    fun `paragraph agent wins and span fallback uses first main span agent`() {
        val lines = parse("""<p begin="1s" ttm:agent="first"><span begin="1s" ttm:agent="ignored">Main</span></p>
            <p begin="2s"><span ttm:role="x-bg" ttm:agent="ignored">Echo</span><span begin="2s" ttm:agent="second">Hello</span> <span begin="2.5s" ttm:agent="third">world</span></p>
            <p begin="3s" ttm:agent="third">Third voice</p>""")
        assertEquals(listOf(LyricVoice("first", LyricVoice.Kind.PRIMARY),
            LyricVoice("second", LyricVoice.Kind.SECONDARY), LyricVoice("third", LyricVoice.Kind.PRIMARY)), lines.map { it.voice })
        assertEquals(listOf("Hello", "world"), lines[1].words.map { it.text })
    }

    private fun parse(body: String) = LyricsStore.parseLyricsText(
        """<?xml version="1.0"?><tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata"><body><div>$body</div></body></tt>""")

    @Test
    fun `timed spans preserve separation and omit background and translation`() {
        val line = parse("""<p begin="00:00:12.000" end="00:15"><span begin="12s">Hello</span> <span begin="00:12.500">world</span><span begin="13s" ttm:role="x-bg">echo</span><span ttm:role="x-translation">bonjour</span></p>""").single()
        assertEquals("Hello world", line.text)
        assertEquals(12000L, line.startMs)
        assertEquals(listOf(TimedWord(12000, "Hello"), TimedWord(12500, "world")), line.words)
        assertTrue(line.isSynced)
        assertEquals(listOf(KaraokeFill(0, 5, 0.4f), KaraokeFill(6, 11, 0f)),
            karaokeFills(TimedLyricLine(line.startMs, line.text, words = line.words), 12200))
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
        assertEquals(listOf(KaraokeFill(0, 5, 0.5f), KaraokeFill(6, 11, 0f)),
            karaokeFills(TimedLyricLine(line.startMs, line.text, words = line.words), 12250))
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
