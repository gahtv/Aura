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
    fun `full and short clocks plus all offset units use default rates`() {
        val times = mapOf(
            "01:02:03" to 3723000L, "00:01:02.345" to 62345L, "01:02.345" to 62345L,
            "00:00:02:15" to 2500L, "00:00:02:15.0" to 2500L,
            "1.25h" to 4500000L, "1.25m" to 75000L, "12.5s" to 12500L,
            "125.5ms" to 126L, "15.5f" to 517L, "1.5t" to 1500L
        )
        val lines = parse(times.keys.joinToString("") { """<p begin="$it">$it</p>""" })
        assertTrue(lines.all { it.isSynced })
        assertEquals(times, lines.associate { it.text to it.startMs })
    }

    @Test
    fun `root timing parameters apply to frames subframes and ticks`() {
        val body = """<p begin="00:00:02:12.2">Clock</p><p begin="12.5f">Frames</p><p begin="100t">Ticks</p>"""
        val rates = """xmlns:params="http://www.w3.org/ns/ttml#parameter" params:frameRate="25" params:frameRateMultiplier="2 1" params:subFrameRate="4""""
        fun parseWithRates(attributes: String) = LyricsStore.parseLyricsText(
            """<tt $rates $attributes><body><div>$body</div></body></tt>"""
        ).associate { it.text to it.startMs }

        assertEquals(mapOf("Clock" to 2250L, "Frames" to 250L, "Ticks" to 500L), parseWithRates(""))
        assertEquals(mapOf("Clock" to 2250L, "Frames" to 250L, "Ticks" to 1000L), parseWithRates("""params:tickRate="100""""))
    }

    @Test
    fun `parallel containers resolve nested starts on the playback clock`() {
        val lines = LyricsStore.parseLyricsText("""<tt><body begin="5s"><div begin="2s"><div begin="500ms">
            <p begin="1s" timeContainer="par"><span begin="1.5s">Hello</span> <span><span begin="2s">world</span></span></p>
            <p>Inherited</p></div><p begin="4s">Sibling</p></div></body></tt>""")
        assertEquals(listOf(7500L, 8500L, 11000L), lines.map { it.startMs })
        assertEquals(listOf(TimedWord(10000, "Hello"), TimedWord(10500, "world")), lines[1].words)
    }

    @Test
    fun `end is relative to the container while dur is relative to the element start`() {
        val lines = LyricsStore.parseLyricsText("""<tt><body begin="2s">
            <div begin="3s" dur="2s"><p begin="1s">Inside duration</p><p begin="2s">After duration</p></div>
            <div begin="6s" end="8s"><p begin="1s">Inside end</p><p begin="2s">At end</p></div>
            </body></tt>""")
        assertEquals(listOf("Inside duration", "Inside end"), lines.map { it.text })
        assertEquals(listOf(6000L, 9000L), lines.map { it.startMs })
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
