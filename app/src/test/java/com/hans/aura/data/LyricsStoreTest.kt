package com.hans.aura.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** LRC parsing: synced stamps, multi-stamp lines, and the plain-text fallback. */
class LyricsStoreTest {
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
        assertEquals(listOf(TimedWord(10000, "Hello"), TimedWord(11000, "world")), lines[0].words)
        assertEquals(listOf(TimedWord(20000, "Hello"), TimedWord(21000, "world")), lines[1].words)
    }

    @Test
    fun `enhanced lrc strips tags and parses words including millisecond precision`() {
        val line = LyricsStore.parseLrcText("[00:12.00]<00:12>Hello  <00:12.503>world<00:13.10>  ").single()
        assertEquals("Hello world", line.text)
        assertEquals(listOf(TimedWord(12000, "Hello"), TimedWord(12503, "world")), line.words)
        assertEquals(listOf(KaraokeSpan("Hello", true), KaraokeSpan(" world", false)), karaokeSpans(
            TimedLyricLine(line.startMs, line.text, words = line.words), 12200))
        assertFalse(line.text.contains('<'))
    }

    @Test
    fun `enhanced multi stamp copies shift word times`() {
        val lines = LyricsStore.parseLrcText("[00:10][00:20]<00:10.25>Hello <00:11>world<00:12>")
        assertEquals(listOf(10250L, 11000L), lines[0].words.map { it.startMs })
        assertEquals(listOf(20250L, 21000L), lines[1].words.map { it.startMs })
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
