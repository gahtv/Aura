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
    fun `adjacent ttml syllables have independent ranges and fractions`() {
        val line = parse("""<p begin="1s"><span begin="1s" end="1.4s">Hol</span><span begin="1.4s" end="2s">ding</span> <span begin="2s" end="2.5s">on</span></p>""").single().toTimedLyricLine()
        assertEquals("Holding on", line.text)
        assertEquals(listOf(KaraokeFill(0, 3, 0.5f), KaraokeFill(3, 7, 0f), KaraokeFill(8, 10, 0f)), karaokeFills(line, 1200))
        assertEquals(listOf(KaraokeFill(0, 3, 1f), KaraokeFill(3, 7, 0.5f), KaraokeFill(8, 10, 0f)), karaokeFills(line, 1700))
        assertEquals(listOf(KaraokeFill(0, 3, 1f), KaraokeFill(3, 7, 1f), KaraokeFill(8, 10, 0.5f)), karaokeFills(line, 2250))
    }

    @Test
    fun `word end and duration survive absolute and relative timing`() {
        val lines = parse("""<p begin="1s"><span begin="1s" end="2s" dur="5s">End</span> <span begin="2s" dur="500ms">Duration</span></p>
            <p begin="10s"><span begin="0s" end="500ms">Relative</span> <span begin="500ms" dur="250ms">duration</span></p>""")
        assertEquals(listOf(TimedWord(1000, "End", 2000), TimedWord(2000, "Duration", 2500)), lines[0].words)
        assertEquals(listOf(TimedWord(10000, "Relative", 10500), TimedWord(10500, "duration", 10750)), lines[1].words)
    }

    @Test
    fun `word ends use container timing rates and duration bounds`() {
        val lines = LyricsStore.parseLyricsText("""<tt xmlns:ttp="http://www.w3.org/ns/ttml#parameter" ttp:frameRate="25" ttp:tickRate="100">
            <body begin="2s"><div begin="3s"><p begin="1s" end="5s" timeContainer="par">
            <span begin="25f" end="50f">Frames</span> <span begin="75f" dur="200t">Ticks</span>
            </p></div></body></tt>""")
        val line = lines.single()
        assertEquals(6000L, line.startMs)
        assertEquals(listOf(TimedWord(7000, "Frames", 8000), TimedWord(9000, "Ticks", 10000)), line.words)
        assertEquals(listOf(KaraokeFill(0, 6, 0.5f), KaraokeFill(7, 12, 0f)),
            karaokeFills(TimedLyricLine(line.startMs, line.text, words = line.words), 7500))
    }

    @Test
    fun `word ends respect nested spans and legacy track relative paragraph bounds`() {
        val nested = parse("""<p begin="6s" timeContainer="par"><span end="2500ms"><span begin="1s" dur="5s">Nested</span></span></p>""").single()
        assertEquals(listOf(TimedWord(7000, "Nested", 8500)), nested.words)
        val legacy = parse("""<p begin="12s" end="15s"><span begin="12s" end="13s">Hello</span> <span begin="13s" dur="5s">world</span></p>""").single()
        assertEquals(listOf(TimedWord(12000, "Hello", 13000), TimedWord(13000, "world", 15000)), legacy.words)
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
    fun `timed spans keep background separate and omit translation`() {
        val line = parse("""<p begin="00:00:12.000" end="00:15"><span begin="12s">Hello</span> <span begin="00:12.500">world</span><span begin="13s" ttm:role="x-bg">echo</span><span ttm:role="x-translation">bonjour</span></p>""").single()
        assertEquals("Hello world", line.text)
        assertEquals(listOf(AdlibSegment("echo", 13000)), line.adlib?.segments)
        assertEquals(12000L, line.startMs)
        assertEquals(listOf(TimedWord(12000, "Hello"), TimedWord(12500, "world")), line.words)
        assertTrue(line.isSynced)
        assertEquals(listOf(KaraokeFill(0, 5, 0.4f), KaraokeFill(6, 11, 0f)),
            karaokeFills(line.toTimedLyricLine(), 12200))
    }

    @Test
    fun `nested background spans stay on their own line without leaking across paragraphs`() {
        val lines = parse("""
            <p begin="2s"><span begin="2s">Lead</span>
                <span ttm:role="x-bg"><span begin="2.1s">(Oh</span><br/><span begin="2.2s">yeah)</span>
                    <span ttm:role="x-translation">Ignored</span></span>
                <span ttm:role="x-bg">(again)</span>
            </p>
            <p begin="4s">Next<span ttm:role="x-bg"> </span></p>
            <p begin="5s">Last</p>
        """)
        assertEquals(listOf("Lead", "Next", "Last"), lines.map { it.text })
        assertEquals(listOf("(Oh yeah) (again)", null, null), lines.map { it.adlib?.text })
        assertEquals(
            listOf(AdlibSegment("(Oh", 2100), AdlibSegment(" yeah)", 2200), AdlibSegment(" (again)")),
            lines.first().adlib?.segments
        )
        assertEquals(listOf(TimedWord(2000, "Lead")), lines.first().words)
    }

    @Test
    fun `adlib syllables retain punctuation and highlight before and beyond their lead line`() {
        val line = parse("""
            <p begin="12s"><span begin="12s">Lead</span>
                <span ttm:role="x-bg" begin="11.5s" end="13.4s">(<span begin="11.6s">La</span><span begin="11.8s">la</span> <span begin="13s"><span>land</span>)</span></span>
            </p>
            <p begin="12.9s">Next lead</p>
        """).first().toTimedLyricLine()
        val adlib = requireNotNull(line.adlib)
        assertEquals("(Lala land)", adlib.text)
        assertEquals(listOf(0f, 0f, 0f, 0f, 0f), adlib.karaokeFills(11499, false).map { it.fraction })
        assertEquals(listOf(1f, 0f, 0f, 0f, 0f), adlib.karaokeFills(11600, false).map { it.fraction })
        assertEquals(listOf(1f, 0.5f, 0f, 0f, 0f), adlib.karaokeFills(11700, false).map { it.fraction })
        assertEquals(listOf(1f, 1f, 0f, 0f, 0f), adlib.karaokeFills(11800, false).map { it.fraction })
        assertEquals(listOf(1f, 1f, 1f, 0f, 0f), adlib.karaokeFills(13000, false).map { it.fraction })
        assertEquals(listOf(1f, 1f, 1f, 0.5f, 0.5f), adlib.karaokeFills(13200, false).map { it.fraction })
        assertEquals(listOf(0f, 0f, 0f, 0f, 0f), adlib.karaokeFills(13400, false).map { it.fraction })
        assertEquals(listOf(KaraokeFill(0, 4, 0f)), karaokeFills(line, 11800))
    }

    @Test
    fun `explicit parallel timing resolves adlib offsets and duration like lead offsets`() {
        val line = parse("""
            <div begin="2s"><p begin="10s" timeContainer="par"><span begin="500ms">Lead</span>
                <span ttm:role="x-bg" begin="30f" dur="1s"><span>Oh</span> <span begin="500ms">yeah</span></span>
            </p></div>
        """).single()
        assertEquals(listOf(TimedWord(12500, "Lead")), line.words)
        assertEquals(LyricAdlib(listOf(AdlibSegment("Oh", 13000), AdlibSegment(" yeah", 13500)), 14000), line.adlib)
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
    fun `the earlier of end and dur bounds a container`() {
        val lines = parse("""<div begin="10s" end="20s" dur="5s">
            <p begin="4s">Before duration</p><p begin="6s">After duration</p></div>
            <div begin="20s" end="25s" dur="10s">
            <p begin="4s">Before end</p><p begin="6s">After end</p></div>""")
        assertEquals(listOf("Before duration", "Before end"), lines.map { it.text })
        assertEquals(listOf(14000L, 24000L), lines.map { it.startMs })
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
    fun `span time that rounds to the paragraph start stays absolute`() {
        val line = parse("""<p begin="12s"><span begin="11.9999s">Hello</span></p>""").single()
        assertEquals(listOf(TimedWord(12000, "Hello")), line.words)
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
