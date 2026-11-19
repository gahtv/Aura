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
        assertEquals(LyricAdlib(listOf(AdlibSegment("Bon", 4100, 4500), AdlibSegment("jour", 4500, 5000)), 5000), translation(lines[1]))
        assertEquals(null, translation(lines[2]))
        assertEquals(lines[1].translation, lines[1].toTimedLyricLine().translation)
        assertEquals(listOf(TimedWord(2000, "Lead")), lines.first().words)
    }

    @Test
    fun `equal lead lines retain distinct metadata translation keys`() {
        val lines = LyricsStore.parseLyricsText("""<tt xmlns:itunes="http://music.apple.com/lyric-ttml-internal">
            <head><metadata><iTunesMetadata><translations><translation xml:lang="en">
            <text for="L1">First translation</text><text for="L2">Second translation</text>
            </translation></translations></iTunesMetadata></metadata></head>
            <body><p begin="2s" itunes:key="L1">Same lead</p><p begin="2s" itunes:key="L2">Same lead</p></body></tt>""")
        assertEquals(listOf("Same lead", "Same lead"), lines.map { it.text })
        assertEquals(listOf("First translation", "Second translation"), lines.map { it.translation?.text })
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
            assertEquals(LyricAdlib(listOf(AdlibSegment("Hello", 2000, 3000), AdlibSegment(" world", 3000, 4000)), 4000), translation(lines.first()))
            assertEquals(null, translation(lines.last()))
            java.util.Locale.setDefault(java.util.Locale.GERMAN)
            assertEquals("Bonjour", translation(translated().first())?.text)
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }

    @Test
    fun `Apple song parts keep word and adlib ends on the track clock with duration bounds`() {
        for (namespace in listOf("http://music.apple.com/lyric-ttml-internal",
            "http://music.apple.com/lyrics", "http://itunes.apple.com/lyric-ttml-extensions")) {
            for (container in listOf("", "timeContainer=\"par\"")) {
                val line = LyricsStore.parseLyricsText("""<tt xmlns:apple="$namespace" xmlns:ttm="http://www.w3.org/ns/ttml#metadata"><body begin="2s"><div begin="12s" end="30s" apple:song-part="Chorus"><p begin="12.5s" end="20s" $container><span begin="12.5s" end="14.5s" dur="5s">Hold</span> <span begin="14.5s" dur="2s">on</span><span ttm:role="x-bg" begin="13s" end="19s"><span begin="13s" dur="2s">Echo</span> <span begin="16s" end="17s">reply</span> <span begin="18s" dur="5s">tail</span></span></p></div></body></tt>""").single().toTimedLyricLine()
                assertEquals(12500L, line.startMs)
                assertEquals(listOf(TimedWord(12500, "Hold", 14500), TimedWord(14500, "on", 16500)), line.words)
                assertEquals(listOf(KaraokeFill(0, 4, 0.75f), KaraokeFill(5, 7, 0f)), karaokeFills(line, 14000))
                val adlib = requireNotNull(line.adlib)
                assertEquals(listOf(AdlibSegment("Echo", 13000, 15000), AdlibSegment(" reply", 16000, 17000),
                    AdlibSegment(" tail", 18000, 19000)), adlib.segments)
                assertEquals(19000L, adlib.endMs)
                assertEquals(listOf(KaraokeFill(0, 4, 0.5f), KaraokeFill(5, 10, 0f), KaraokeFill(11, 15, 0f)), adlib.karaokeFills(14000, true))
                assertEquals(1f, adlib.karaokeFills(15500, true).first().fraction)
            }
        }
    }

    @Test
    fun `background ends inherit through untimed children and respect legacy clocks and bounds`() {
        val legacy = parse("""<p begin="12s" end="18s"><span begin="12s">Lead</span><span ttm:role="x-bg" begin="12.5s" dur="2s"><span>Echo</span></span></p>""").single().adlib!!
        assertEquals(listOf(AdlibSegment("Echo", 12500, 14500)), legacy.segments)
        assertEquals(0.5f, legacy.karaokeFills(13500, true).single().fraction)
        val bounded = parse("""<p begin="5s" end="9s" timeContainer="par">Lead<span ttm:role="x-bg" begin="1s" dur="5s"><span begin="1s" dur="10s"><span>Echo</span></span></span></p>""").single().adlib!!
        assertEquals(listOf(AdlibSegment("Echo", 7000, 9000)), bounded.segments)
        assertEquals(0.5f, bounded.karaokeFills(8000, true).single().fraction)
    }

    @Test
    fun `explicit background ends and durations control fill before gaps with timing rates`() {
        val line = LyricsStore.parseLyricsText("""<tt xmlns:ttp="http://www.w3.org/ns/ttml#parameter" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" ttp:frameRate="25" ttp:tickRate="100"><body begin="2s"><div begin="3s"><p begin="1s" timeContainer="par">Lead<span ttm:role="x-bg"><span begin="25f" dur="200t">Holding</span> <span begin="150f" end="200f">on</span> <span begin="250f">again</span></span></p></div></body></tt>""").single()
        val adlib = requireNotNull(line.adlib)
        assertEquals(0.5f, adlib.karaokeFills(8000, true)[0].fraction)
        assertEquals(1f, adlib.karaokeFills(10000, true)[0].fraction)
        assertEquals(0.5f, adlib.karaokeFills(13000, true)[1].fraction)
        assertEquals(1f, adlib.karaokeFills(15000, true)[1].fraction)
        val finalWord = parse("""<p begin="5s" timeContainer="par">Lead<span ttm:role="x-bg" begin="1s" dur="2s">Echo</span></p>""").single().adlib!!
        assertEquals(0.5f, finalWord.karaokeFills(7000, true).single().fraction)
    }

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
    fun `Apple song part line and word starts and ends use the track clock`() {
        val fixture = checkNotNull(javaClass.getResource("/apple-song-parts.ttml")).readText()
        val lines = LyricsStore.parseLyricsText(fixture)

        assertEquals(listOf(2000L, 12500L, 17000L, 25000L), lines.map { it.startMs })
        assertEquals(listOf("First light", "Turn around", "Sing again", "Last light"), lines.map { it.text })
        assertEquals(listOf(
            listOf(TimedWord(2000, "First", 4500), TimedWord(4500, "light", 5000)),
            listOf(TimedWord(12500, "Turn", 13750), TimedWord(13750, "around", 15000)),
            listOf(TimedWord(17000, "Sing", 18250), TimedWord(18250, "again", 20000)),
            listOf(TimedWord(25000, "Last", 26500), TimedWord(26500, "light", 28000))
        ), lines.map { it.words })
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

    @Test
    fun `each div is its own song part`() {
        val lines = LyricsStore.parseLyricsText("""<tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal"><body>
            <div begin="1s" itunes:song-part="Verse"><p begin="1s">Verse one</p><p begin="2s">Verse two</p></div>
            <div begin="3s" itunes:song-part="Chorus"><p begin="3s">Chorus one</p></div>
            <div begin="4s" itunes:song-part="Verse"><p begin="4s">Verse three</p></div></body></tt>""")
        assertEquals(listOf("Verse one", "Verse two", "Chorus one", "Verse three"), lines.map { it.text })
        assertEquals(listOf(1, 1, 2, 3), lines.map { it.part })
    }

    @Test
    fun `lines in a single div share one part`() {
        assertEquals(setOf(1), parse("""<p begin="1s">One</p><p begin="2s">Two</p>""").map { it.part }.toSet())
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
        assertEquals(listOf(100f / 1900f, 0f, 0f, 0f, 0f), adlib.karaokeFills(11600, false).map { it.fraction })
        assertEquals(listOf(200f / 1900f, 0.5f, 0f, 0f, 0f), adlib.karaokeFills(11700, false).map { it.fraction })
        assertEquals(listOf(300f / 1900f, 1f, 0f, 0f, 0f), adlib.karaokeFills(11800, false).map { it.fraction })
        assertEquals(listOf(1500f / 1900f, 1f, 1f, 0f, 0f), adlib.karaokeFills(13000, false).map { it.fraction })
        assertEquals(listOf(1700f / 1900f, 1f, 1f, 0.5f, 0.5f), adlib.karaokeFills(13200, false).map { it.fraction })
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
        assertEquals(LyricAdlib(listOf(AdlibSegment("Oh", 13000, 14000), AdlibSegment(" yeah", 13500)), 14000), line.adlib)
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

    @Test
    fun `ttml end, dur and span ends become line ends`() {
        val lines = parse(
            """<p begin="1s" end="3s">End</p><p begin="4s" dur="2s">Dur</p>""" +
                """<p begin="10s"><span begin="10s" end="11s">Abs</span> <span begin="11s" end="12.5s">spans</span></p>""" +
                """<p begin="20s"><span begin="0s" end="1s">Rel</span> <span begin="1s" end="2s">spans</span></p>""" +
                """<p begin="30s">Open</p>""" +
                """<p begin="40s"><span begin="40s" end="41s">Last</span> <span begin="41s">open</span></p>""")
        assertEquals(listOf(3_000L, 6_000L, 12_500L, 22_000L, null, null), lines.map { it.endMs })
    }
}
