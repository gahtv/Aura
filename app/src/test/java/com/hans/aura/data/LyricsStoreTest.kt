package com.hans.aura.data

import android.os.Looper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList

/** LRC parsing: synced stamps, multi-stamp lines, and the plain-text fallback. */
@RunWith(RobolectricTestRunner::class)
class LyricsStoreTest {
    @Test
    fun `fetch preserves raw LRC and TTML parsing`() = runBlocking {
        for (raw in listOf(
            "[00:01]<00:01>Hello <00:02>world<00:03>",
            """<tt><body><div><p begin="1s" end="3s"><span begin="1s">Hello</span> <span begin="2s">world</span></p></div></body></tt>"""
        )) {
            val api = FakeJellyfinApi(lyricsResponse = LyricsResponse(lyricText = raw))
            val lines = LyricsStore.fetch(api, "track")
            assertEquals(LyricsStore.parseLyricsText(raw), lines)
            assertEquals("Hello world", lines.single().text)
            assertEquals(listOf(1000L, 2000L), lines.single().words.map { it.startMs })
        }
    }

    @Test
    fun `fetch handles empty responses and network errors without swallowing cancellation`() = runBlocking {
        assertTrue(LyricsStore.fetch(FakeJellyfinApi(lyricsResponse = LyricsResponse()), "track").isEmpty())
        fun failingApi(error: Exception) = object : JellyfinApi by FakeJellyfinApi() {
            override suspend fun getLyrics(itemId: String): LyricsResponse = throw error
        }
        assertTrue(LyricsStore.fetch(failingApi(IllegalStateException("offline")), "track").isEmpty())
        val cancelled = CancellationException("cancelled")
        try {
            LyricsStore.fetch(failingApi(cancelled), "track")
            fail("Cancellation was swallowed")
        } catch (e: CancellationException) {
            assertSame(cancelled, e)
        }
    }

    @Test
    fun `fetch parses response off the caller thread`() = runBlocking {
        val caller = Thread.currentThread()
        assertSame(Looper.getMainLooper().thread, caller)
        val parseThreads = CopyOnWriteArrayList<Thread>()
        val dtoLines = object : AbstractList<LyricLineDto>() {
            override val size = 1
            override fun get(index: Int): LyricLineDto {
                parseThreads += Thread.currentThread()
                return LyricLineDto("<00:01>Hello <00:02>world<00:03>", 10000000L)
            }
        }
        val response = LyricsResponse(lyrics = dtoLines)

        val lines = LyricsStore.fetch(FakeJellyfinApi(lyricsResponse = response), "track")

        assertEquals("Hello world", lines.single().text)
        assertEquals(listOf(1000L, 2000L), lines.single().words.map { it.startMs })
        assertEquals(3000L, lines.single().endMs)
        assertTrue(parseThreads.isNotEmpty())
        assertTrue("Parsing ran on the caller thread", parseThreads.none { it === caller })
    }

    @Test
    fun `cue-only and inline-only DTO lines without line stamps stay separate`() = runBlocking {
        for (response in listOf(
            LyricsResponse(lyrics = listOf(
                LyricLineDto("First", cues = listOf(LyricCueDto(0, 5, 10000000))),
                LyricLineDto("Second", cues = listOf(LyricCueDto(0, 6, 20000000)))
            )),
            LyricsResponse(lyrics = listOf(LyricLineDto("<00:01>First"), LyricLineDto("<00:02>Second")))
        )) {
            val lines = LyricsStore.fetch(FakeJellyfinApi(lyricsResponse = response), "track")
            assertEquals(listOf("First", "Second"), lines.map { it.text })
            assertEquals(listOf(1000L, 2000L), lines.map { it.words.single().startMs })
            assertTrue(lines.all { it.isSynced && it.translation == null })
        }
    }

    @Test
    fun `raw and structured LRC pair only first adjacent same stamp follower`() = runBlocking {
        val entries = listOf(
            12000L to "v1: Across the sky",
            12000L to "A travers le ciel",
            12000L to "Third line",
            12000L to "Fourth line",
            20000L to "v1: Duet lead",
            20000L to "v2: Duet reply",
            30000L to "",
            30000L to "After blank",
            40000L to "Before blank",
            40000L to " ",
            50000L to "<00:50>Hello <00:51>world",
            50000L to "<00:50.2>Bon<00:50.5>jour <00:51>monde"
        )
        val raw = entries.joinToString("\n") { (time, text) -> "[00:${time / 1000}]$text" }
        val response = LyricsResponse(lyrics = entries.map { (time, text) -> LyricLineDto(text, time * 10000) })
        for (lines in listOf(LyricsStore.parseLrcText(raw), LyricsStore.fetch(FakeJellyfinApi(lyricsResponse = response), "track"))) {
            assertEquals(listOf("Across the sky", "Third line", "Fourth line", "Duet lead", "Duet reply", "After blank", "Before blank", "Hello world"), lines.map { it.text })
            assertEquals(30000L, lines.single { it.text == "Duet reply" }.endMs)
            assertEquals("A travers le ciel", lines.first().translation?.text)
            assertFalse(requireNotNull(lines.first().translation).isTimed)
            assertEquals(LyricVoice("v1", LyricVoice.Kind.PRIMARY), lines.first().voice)
            assertEquals(listOf(AdlibSegment("Bon", 50200), AdlibSegment("jour", 50500), AdlibSegment(" monde", 51000)), lines.last().translation?.segments)
            assertEquals(listOf(TimedWord(50000, "Hello"), TimedWord(51000, "world")), lines.last().words)
        }
    }

    @Test
    fun `same voice translations keep cue timing and offset while unsynced lines block pairing`() = runBlocking {
        val response = LyricsResponse(metadata = LyricMetadataDto(offset = 10000000), lyrics = listOf(
            LyricLineDto("v1: Lead", 50000000),
            LyricLineDto("v1: Bon jour", 50000000, listOf(LyricCueDto(4, 7, 50000000), LyricCueDto(8, 12, 55000000))),
            LyricLineDto("Separate", 60000000), LyricLineDto("Plain"), LyricLineDto("Not translation", 60000000)
        ))
        val lines = LyricsStore.fetch(FakeJellyfinApi(lyricsResponse = response), "track")
        val lead = lines.single { it.text == "Lead" }
        assertEquals(listOf(AdlibSegment("Bon", 4000), AdlibSegment(" jour", 4500)), lead.translation?.segments)
        assertEquals(4000L, lead.startMs)
        assertEquals(listOf("Plain", "Lead", "Separate", "Not translation"), lines.map { it.text })
        assertEquals(3, LyricsStore.parseLrcText("[00:01]Lead\nPlain\n[00:01]Reply\n[00:02]Last").size)
        val headers = LyricsResponse(lyrics = listOf(LyricLineDto("Lead", 10000000), LyricLineDto("[au:Writer]", 10000000)))
        val headerLead = LyricsStore.fetch(FakeJellyfinApi(lyricsResponse = headers), "track").single()
        assertEquals(null, headerLead.translation)
        assertEquals(listOf("Writer"), headerLead.credits.writers)
    }

    @Test
    fun `pairing precedes sorting and offset clamping and preserves repeated stamps and credits`() = runBlocking {
        val raw = "[au:Writer]\n[offset:15000]\n[00:30]Later\n[00:10][00:20]Chorus\n[00:20]Translation\n[00:10]Separate"
        val dto = LyricsResponse(metadata = LyricMetadataDto(offset = 150000000), lyrics = listOf(
            LyricLineDto("[au:Writer]"), LyricLineDto("Later", 300000000),
            LyricLineDto("Chorus", 100000000), LyricLineDto("Chorus", 200000000),
            LyricLineDto("Translation", 200000000), LyricLineDto("Separate", 100000000)
        ))
        for (lines in listOf(LyricsStore.parseLrcText(raw), LyricsStore.fetch(FakeJellyfinApi(lyricsResponse = dto), "track"))) {
            assertEquals(listOf("Chorus", "Separate", "Chorus", "Later"), lines.map { it.text })
            assertEquals(listOf(0L, 0L, 5000L, 15000L), lines.map { it.startMs })
            assertEquals(listOf(null, null, "Translation", null), lines.map { it.translation?.text })
            assertTrue(lines.all { it.credits.writers == listOf("Writer") })
        }
    }

    @Test
    fun `repeated stamp translations pair with every copy of their lead`() = runBlocking {
        val raw = "[00:10][00:20]Hello\n[00:10][00:20]Bonjour\n[00:30]Same\n[00:30]Same"
        val dto = LyricsResponse(lyrics = listOf(
            LyricLineDto("Hello", 100000000), LyricLineDto("Hello", 200000000),
            LyricLineDto("Bonjour", 100000000), LyricLineDto("Bonjour", 200000000),
            LyricLineDto("Same", 300000000), LyricLineDto("Same", 300000000)
        ))
        for (lines in listOf(LyricsStore.parseLrcText(raw), LyricsStore.fetch(FakeJellyfinApi(lyricsResponse = dto), "track"))) {
            assertEquals(listOf("Hello", "Hello", "Same"), lines.map { it.text })
            assertEquals(listOf(10000L, 20000L, 30000L), lines.map { it.startMs })
            assertEquals(listOf("Bonjour", "Bonjour", "Same"), lines.map { it.translation?.text })
        }
    }

    @Test
    fun `partially matching repeated stamps pair only the matching copies`() = runBlocking {
        val raw = "[00:10]Hello\n[00:10][00:20]Bonjour"
        val dto = LyricsResponse(lyrics = listOf(
            LyricLineDto("Hello", 100000000), LyricLineDto("Bonjour", 100000000), LyricLineDto("Bonjour", 200000000)
        ))
        for (lines in listOf(LyricsStore.parseLrcText(raw), LyricsStore.fetch(FakeJellyfinApi(lyricsResponse = dto), "track"))) {
            assertEquals(listOf("Hello", "Bonjour"), lines.map { it.text })
            assertEquals(listOf(10000L, 20000L), lines.map { it.startMs })
            assertEquals(listOf("Bonjour", null), lines.map { it.translation?.text })
        }
    }

    @Test
    fun `file offsets shift trailing ends with repeated word starts`() {
        for (offset in listOf(1500L, -1500L, 5000L)) {
            val lines = LyricsStore.parseLrcText("[00:01][00:03]<00:01>One <00:02>two<00:04>\n[offset:$offset]")
            assertEquals(listOf(2000L, 4000L).map { (it - offset).coerceAtLeast(0L) },
                lines.map { it.words.last().startMs })
            assertEquals(listOf(4000L, 6000L).map { (it - offset).coerceAtLeast(0L) },
                lines.map { it.words.last().endMs })
        }
    }

    @Test
    fun `structured offsets shift cue and inline trailing ends`() = runBlocking {
        for (offset in listOf(15000000L, -15000000L, 50000000L)) {
            val response = Json { ignoreUnknownKeys = true }.decodeFromString<LyricsResponse>(
                """{"Metadata":{"Offset":$offset},"Lyrics":[{"Text":"One","Start":10000000,"Cues":[{"Position":0,"EndPosition":3,"Start":10000000,"End":30000000}]},{"Text":"v2: <00:01>One<00:03>","Start":10000000},{"Text":"v2: <00:01>One<00:03>","Start":40000000}]}"""
            )
            val lines = LyricsStore.fetch(FakeJellyfinApi(lyricsResponse = response), "track")
            assertEquals(listOf(1000L, 1000L, 4000L).map { (it - offset / 10000L).coerceAtLeast(0L) },
                lines.map { it.words.single().startMs })
            assertEquals(listOf(3000L, 3000L, 6000L).map { (it - offset / 10000L).coerceAtLeast(0L) },
                lines.map { it.words.single().endMs })
        }
    }

    @Test
    fun `LRC credits are retained separately from lyrics`() {
        val lines = LyricsStore.parseLrcText(
            "[ar:Performer][AU: Ada Lane ][au:Ada Lane][lr:Ren Park]\n[by: Sam Ford ][lr: ]\n" +
                "[00:02]Hello\n[00:03]World [au:not a header]"
        )
        assertEquals(listOf("Hello", "World [au:not a header]"), lines.map { it.text })
        assertEquals(
            LyricCredits(listOf("Ada Lane", "Ren Park"), listOf("Sam Ford")),
            lines.first().credits
        )
        assertEquals(lines.first().credits, lines.last().credits)
    }

    @Test
    fun `Jellyfin credits merge with leaked headers in structured and raw lyrics`() = runBlocking {
        for (lyrics in listOf(
            """"Lyrics":[{"Text":"[au:Ada Lane][lr:Ren Park]"},{"Text":"[by:Sam Ford]"},{"Text":"Hello"}]""",
            """"LyricText":"[au:Ada Lane][lr:Ren Park]\n[by:Sam Ford]\nHello""""
        )) {
            val response = Json.decodeFromString<LyricsResponse>(
                """{"Metadata":{"Author":" Ada Lane ","Lyricist":"Jay Poe","By":" "},$lyrics}"""
            )
            val line = LyricsStore.fetch(FakeJellyfinApi(lyricsResponse = response), "track").single()
            assertEquals("Hello", line.text)
            assertEquals(
                LyricCredits(listOf("Ada Lane", "Jay Poe", "Ren Park"), listOf("Sam Ford")),
                line.credits
            )
        }
    }

    @Test
    fun `offset shifts lines and repeated inline words and clamps at zero`() {
        for ((offset, starts, words) in listOf(
            Triple("+1500", listOf(0L, 1500L), listOf(0L, 500L)),
            Triple("-1500", listOf(2500L, 4500L), listOf(2500L, 3500L))
        )) {
            val lines = LyricsStore.parseLrcText("[00:01][00:03]<00:01>One <00:02>two\n[offset:$offset]")
            assertEquals(starts, lines.map { it.startMs })
            assertEquals(words, lines[0].words.map { it.startMs })
            assertEquals(listOf(words[0] + if (offset.startsWith("+")) 1500L else 2000L, words[1] + 2000L),
                lines[1].words.map { it.startMs })
        }
    }

    @Test
    fun `headers never become plain or synced lyrics`() {
        val headers = "[ar:Artist][ti:Title]\n[al:Album]\n[au:Author]\n[by:Writer]\n[re:Editor]\n[ve:1]\n[TOOL:Editor]\n[length:03:00]\n[#:1]\n[offset:+1500]"
        assertTrue(LyricsStore.parseLrcText(headers).isEmpty())
        assertEquals(listOf("Hello"), LyricsStore.parseLrcText("$headers\nHello").map { it.text })
        assertEquals(listOf("Hello"), LyricsStore.parseLrcText("$headers\n[00:02]Hello\n[00:03][ar:Artist]").map { it.text })
        assertEquals(2000L, LyricsStore.parseLrcText("[offset:invalid]\n[00:02]Hello").single().startMs)
    }

    @Test
    fun `section labels remain lyrics in raw and structured paths`() = runBlocking {
        val labels = listOf("[Chorus: Drake]", "[Intro:Kendrick]")
        assertEquals(labels, LyricsStore.parseLrcText(labels.joinToString("\n")).map { it.text })
        val paired = LyricsStore.parseLrcText(labels.joinToString("\n") { "[00:01]$it" }).single()
        assertEquals(labels.first(), paired.text)
        assertEquals(labels.last(), paired.translation?.text)
        val response = LyricsResponse(lyrics = labels.map { LyricLineDto(it) })
        assertEquals(labels, LyricsStore.fetch(FakeJellyfinApi(lyricsResponse = response), "track").map { it.text })
    }

    @Test
    fun `structured Jellyfin metadata offset uses ticks and filters leaked headers`() = runBlocking {
        for ((offset, expected) in listOf(15000000L to 500L, -15000000L to 3500L, 0L to 2000L)) {
            val response = Json { ignoreUnknownKeys = true }.decodeFromString<LyricsResponse>(
                """{"Metadata":{"Offset":$offset},"Lyrics":[{"Text":"[ar:Artist]"},{"Text":"[offset:+1500]"},{"Text":"One","Start":20000000,"Cues":[{"Position":0,"EndPosition":3,"Start":10000000}]}]}"""
            )
            val line = LyricsStore.fetch(FakeJellyfinApi(lyricsResponse = response), "track").single()
            assertEquals(expected, line.startMs)
            assertEquals((1000L - offset / 10000L).coerceAtLeast(0L), line.words.single().startMs)
        }
        val stock = LyricsResponse(lyrics = listOf(LyricLineDto("First", 10000000L)))
        assertEquals(1000L, LyricsStore.fetch(FakeJellyfinApi(lyricsResponse = stock), "track").single().startMs)
        val plain = LyricsResponse(lyrics = listOf(LyricLineDto("[ti:Title]"), LyricLineDto("Hello")))
        assertEquals(listOf("Hello"), LyricsStore.fetch(FakeJellyfinApi(lyricsResponse = plain), "track").map { it.text })
        assertTrue(LyricsStore.fetch(FakeJellyfinApi(), "track").isEmpty())
    }

    @Test
    fun `speaker tags are stripped and classified without inheritance`() {
        val tags = mapOf(
            "v1:" to LyricVoice("v1", LyricVoice.Kind.PRIMARY),
            "V2： " to LyricVoice("v2", LyricVoice.Kind.SECONDARY),
            "v3:" to LyricVoice("v3", LyricVoice.Kind.PRIMARY),
            "v4:" to LyricVoice("v4", LyricVoice.Kind.SECONDARY),
            "v1000:" to LyricVoice("v1000", LyricVoice.Kind.GROUP),
            "F:" to LyricVoice("f", LyricVoice.Kind.PRIMARY),
            "m:" to LyricVoice("m", LyricVoice.Kind.SECONDARY),
            "D:" to LyricVoice("d", LyricVoice.Kind.GROUP),
            "BG:" to LyricVoice("bg", LyricVoice.Kind.GROUP),
            "v1,v2:" to LyricVoice("v1,v2", LyricVoice.Kind.GROUP),
            "v1+v2:" to LyricVoice("v1,v2", LyricVoice.Kind.GROUP),
            "v1&v2:" to LyricVoice("v1,v2", LyricVoice.Kind.GROUP),
            "M/F:" to LyricVoice("m,f", LyricVoice.Kind.GROUP)
        )
        tags.forEach { (tag, voice) ->
            val lines = LyricsStore.parseLrcText("[00:01]${tag}Hello\n[00:02]No tag")
            assertEquals(tag, voice, lines[0].voice)
            assertEquals("Hello", lines[0].text)
            assertEquals(null, lines[1].voice)
        }
        listOf("Me: hello", "Note: x", "v0: hello", "Hello v1: world", "v1 Hello").forEach {
            val line = LyricsStore.parseLrcText("[00:01]$it").single()
            assertEquals(it, line.text)
            assertEquals(null, line.voice)
        }
        assertEquals(LyricVoice("f", LyricVoice.Kind.PRIMARY), LyricsStore.parseLyricsText("F: Plain").single().voice)
    }

    @Test
    fun `enhanced multistamp lyrics preserve voices and clean words`() {
        val lines = LyricsStore.parseLrcText("[00:10][00:20]v2: <00:10>Hello <00:11>world<00:12>")
        assertTrue(lines.all { it.voice == LyricVoice("v2", LyricVoice.Kind.SECONDARY) && it.text == "Hello world" })
        assertEquals(listOf(TimedWord(10000, "Hello"), TimedWord(11000, "world", 12000)), lines[0].words)
        assertEquals(listOf(TimedWord(20000, "Hello"), TimedWord(21000, "world", 22000)), lines[1].words)
    }

    @Test
    fun `enhanced lrc strips tags and parses words including millisecond precision`() {
        val line = LyricsStore.parseLrcText("[00:12.00]<00:12>Hello  <00:12.503>world<00:13.10>  ").single()
        assertEquals("Hello world", line.text)
        assertEquals(listOf(TimedWord(12000, "Hello"), TimedWord(12503, "world", 13100)), line.words)
        val fills = karaokeFills(TimedLyricLine(line.startMs, line.text, words = line.words), 12200)
        assertEquals(200f / 503f, fills.first().fraction, 0.0001f)
        assertEquals(KaraokeFill(6, 11, 0f), fills.last())
        assertFalse(line.text.contains('<'))
    }

    @Test
    fun `enhanced multi stamp copies shift word times`() {
        val lines = LyricsStore.parseLrcText("[00:10][00:20]<00:10.25>Hello <00:11>world<00:12>")
        assertEquals(listOf(10250L, 11000L), lines[0].words.map { it.startMs })
        assertEquals(listOf(20250L, 21000L), lines[1].words.map { it.startMs })
        assertEquals(12000L, lines[0].words.last().endMs)
        assertEquals(22000L, lines[1].words.last().endMs)
        assertTrue(lines.all { it.text == "Hello world" })
        assertTrue(LyricsStore.parseLrcText("[00:01]Plain  line").single().words.isEmpty())
        assertEquals("Plain  line", LyricsStore.parseLrcText("[00:01]Plain  line").single().text)
        assertTrue(LyricsStore.parseLrcText("[00:01]<00:01> <00:02>").isEmpty())
    }

    @Test
    fun `plain text without stamps becomes unsynced lines`() {
        val lines = LyricsStore.parseLrcText("First line\nSecond line")

        assertEquals(2, lines.size)
        assertEquals(listOf("First line", "Second line"), lines.map { it.text })
        assertTrue(lines.none { it.isSynced })
        assertTrue(lines.all { it.startMs == 0L })
    }

    @Test
    fun `synced stamps are parsed to milliseconds`() {
        val lines = LyricsStore.parseLrcText("[00:12.34]Hello\n[00:15.00]World")

        assertEquals(2, lines.size)
        assertEquals(12_340L, lines[0].startMs)
        assertEquals("Hello", lines[0].text)
        assertEquals(15_000L, lines[1].startMs)
        assertTrue(lines.all { it.isSynced })
    }

    @Test
    fun `minutes roll into milliseconds correctly`() {
        val lines = LyricsStore.parseLrcText("[02:05.50]Later")

        assertEquals(1, lines.size)
        assertEquals(125_500L, lines[0].startMs)
    }

    @Test
    fun `a line with several stamps yields one entry per stamp`() {
        val lines = LyricsStore.parseLrcText("[00:10.00][00:20.00]Chorus")

        assertEquals(2, lines.size)
        assertEquals(listOf(10_000L, 20_000L), lines.map { it.startMs })
        assertEquals(listOf("Chorus", "Chorus"), lines.map { it.text })
    }

    @Test
    fun `out-of-order stamps are sorted by time`() {
        val lines = LyricsStore.parseLrcText(
            "[00:30.00]Third\n[00:10.00]First\n[00:20.00]Second"
        )

        assertEquals(listOf("First", "Second", "Third"), lines.map { it.text })
        assertEquals(listOf(10_000L, 20_000L, 30_000L), lines.map { it.startMs })
    }

    @Test
    fun `blank and whitespace-only lines are skipped`() {
        val lines = LyricsStore.parseLrcText("Real line\n\n   \nAnother line")

        assertEquals(listOf("Real line", "Another line"), lines.map { it.text })
        assertTrue(lines.none { it.isSynced })
    }

    @Test
    fun `empty text produces no lines`() {
        assertTrue(LyricsStore.parseLrcText("").isEmpty())
        assertTrue(LyricsStore.parseLrcText("   \n \n").isEmpty())
    }

    @Test
    fun `a mixed file keeps the stamped lines and drops the unstamped ones`() {
        val lines = LyricsStore.parseLrcText("intro note\n[00:05.00]Sung line")

        assertEquals(1, lines.size)
        assertEquals("Sung line", lines[0].text)
        assertTrue(lines[0].isSynced)
    }

    @Test
    fun `stamps using a comma or colon separator are still understood`() {
        // Some writers emit [mm:ss:xx]; the parser normalises ':' to '.'.
        val lines = LyricsStore.parseLrcText("[00:07:50]Odd separator")

        assertEquals(1, lines.size)
        assertEquals(7_500L, lines[0].startMs)
        assertFalse(lines[0].text.contains("["))
    }
}
