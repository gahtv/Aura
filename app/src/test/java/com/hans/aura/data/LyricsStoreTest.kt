package com.hans.aura.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** LRC parsing: synced stamps, multi-stamp lines, and the plain-text fallback. */
class LyricsStoreTest {
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
                """{"Metadata":{"Offset":$offset},"Lyrics":[{"Text":"One","Start":10000000,"Cues":[{"Position":0,"EndPosition":3,"Start":10000000,"End":30000000}]},{"Text":"<00:01>One<00:03>","Start":10000000},{"Text":"<00:01>One<00:03>","Start":40000000}]}"""
            )
            val lines = LyricsStore.fetch(FakeJellyfinApi(lyricsResponse = response), "track")
            assertEquals(listOf(1000L, 1000L, 4000L).map { (it - offset / 10000L).coerceAtLeast(0L) },
                lines.map { it.words.single().startMs })
            assertEquals(listOf(3000L, 3000L, 6000L).map { (it - offset / 10000L).coerceAtLeast(0L) },
                lines.map { it.words.single().endMs })
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
        assertEquals(labels, LyricsStore.parseLrcText(labels.joinToString("\n") { "[00:01]$it" }).map { it.text })
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
        assertTrue(LyricsStore.parseLrcText("[00:01]<00:01> <00:02>").single().words.isEmpty())
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
