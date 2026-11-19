package com.hans.aura.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TtmlLyricsTest {
    private fun translation(line: LyricLine): LyricAdlib? = line.translation

    @Test
    fun `inline translations preserve plain text and timed syllables without changing lead`() {
        val lines = parse("""<p begin="2s"><span begin="2s">Lead</span><span ttm:role="x-bg">Echo</span><span ttm:role="x-translation">Bonjour monde</span></p>
            <p begin="4s">Next<span ttm:role="x-translation"><span begin="4.1s" end="4.5s">Bon</span><span begin="4.5s" end="5s">jour</span></span></p>
            <p begin="6s">Last<span ttm:role="x-translation"> </span></p>""")
        assertEquals(listOf("Lead", "Next", "Last"), lines.map { it.text })
        assertEquals("Echo", lines.first().adlib?.text)
        assertEquals(LyricAdlib(listOf(AdlibSegment("Bonjour monde"))), translation(lines[0]))
        assertEquals(LyricAdlib(listOf(AdlibSegment("Bon", 4100), AdlibSegment("jour", 4500)), 5000), translation(lines[1]))
        assertEquals(null, translation(lines[2]))
        assertEquals(lines[1].translation, lines[1].toTimedLyricLine().translation)
        assertEquals(listOf(TimedWord(2000, "Lead")), lines.first().words)
    }

    @Test
    fun `itunes translations choose locale language or first and retain timings`() {
        val previous = java.util.Locale.getDefault()
        try {
            fun translated() = LyricsStore.parseLyricsText("""<tt xmlns:itunes="http://music.apple.com/lyric-ttml-internal">
                <head><metadata><iTunesMetadata><translations>
                <translation xml:lang="fr"><text for="L1">Bonjour</text></translation>
                <translation xml:lang="en-US"><text for="L1"><span begin="2s" end="3s">Hello</span> <span begin="3s" end="4s">world</span></text></translation>
                </translations></iTunesMetadata></metadata></head>
                <body><p begin="2s" itunes:key="L1">Lead</p><p begin="4s" itunes:key="L2">Next</p></body></tt>""")
            java.util.Locale.setDefault(java.util.Locale.UK)
            val lines = translated()
            assertEquals(listOf("Lead", "Next"), lines.map { it.text })
            assertEquals(LyricAdlib(listOf(AdlibSegment("Hello", 2000), AdlibSegment(" world", 3000)), 4000), translation(lines.first()))
            assertEquals(null, translation(lines.last()))
            java.util.Locale.setDefault(java.util.Locale.GERMAN)
            assertEquals("Bonjour", translation(translated().first())?.text)
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }

    @Test
    fun `iTunes TTML songwriters are credits but performer agents are not`() {
        val line = LyricsStore.parseLyricsText("""
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata"
                xmlns:itunes="http://music.apple.com/lyric-ttml-internal">
                <head><metadata>
                    <ttm:agent xml:id="voice" type="person"><ttm:name>Performer</ttm:name></ttm:agent>
                    <itunes:iTunesMetadata><itunes:songwriters>
                        <itunes:songwriter> Ada   Lane </itunes:songwriter>
                        <itunes:songwriter>Ren Park</itunes:songwriter>
                        <itunes:songwriter>Ada Lane</itunes:songwriter>
                        <itunes:songwriter/>
                    </itunes:songwriters></itunes:iTunesMetadata>
                </metadata></head>
                <body><div><p begin="1s" ttm:agent="voice">Hello</p></div></body>
            </tt>
        """.trimIndent()).single()
        assertEquals("Hello", line.text)
        assertEquals(LyricCredits(writers = listOf("Ada Lane", "Ren Park")), line.credits)
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
    fun `timed spans keep background and translation separate`() {
        val line = parse("""<p begin="00:00:12.000" end="00:15"><span begin="12s">Hello</span> <span begin="00:12.500">world</span><span begin="13s" ttm:role="x-bg">echo</span><span ttm:role="x-translation">bonjour</span></p>""").single()
        assertEquals("Hello world", line.text)
        assertEquals(listOf(AdlibSegment("echo", 13000)), line.adlib?.segments)
        assertEquals(12000L, line.startMs)
        assertEquals(listOf(TimedWord(12000, "Hello"), TimedWord(12500, "world")), line.words)
        assertTrue(line.isSynced)
        assertEquals(listOf(KaraokeSpan("Hello", true), KaraokeSpan(" world", false)),
            karaokeSpans(line.toTimedLyricLine(), 12200))
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
        assertEquals(listOf(false, false, false, false, false), adlib.karaokeSpans(11499, false).map { it.sung })
        assertEquals(listOf(true, true, false, false, false), adlib.karaokeSpans(11600, false).map { it.sung })
        assertEquals(listOf(true, true, true, false, false), adlib.karaokeSpans(11800, false).map { it.sung })
        assertEquals(listOf(true, true, true, true, true), adlib.karaokeSpans(13000, false).map { it.sung })
        assertEquals(listOf(false, false, false, false, false), adlib.karaokeSpans(13400, false).map { it.sung })
        assertEquals(listOf(KaraokeSpan("Lead", false)), karaokeSpans(line, 11800))
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
