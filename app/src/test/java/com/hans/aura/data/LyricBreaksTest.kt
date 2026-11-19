package com.hans.aura.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Line end times from every source, and the instrumental breaks derived from them. */
class LyricBreaksTest {
    private fun line(startMs: Long, endMs: Long? = null) = LyricLine(startMs, "l$startMs", isSynced = true, endMs = endMs)

    @Test
    fun `long intro and long gaps after ended lines are breaks`() {
        val breaks = lyricBreaks(listOf(line(10_000, 14_000), line(14_000, 22_000), line(34_000), line(38_000)))
        assertEquals(listOf(LyricBreak(-1, 0, 10_000), LyricBreak(1, 22_000, 34_000)), breaks)
    }

    @Test
    fun `short silences and untimed line ends are not breaks`() {
        assertTrue(lyricBreaks(listOf(line(3_999, 5_000), line(8_999))).isEmpty())
        assertTrue(lyricBreaks(listOf(line(0), line(30_000), line(60_000))).isEmpty())
    }

    @Test
    fun `an earlier line still being sung holds off the break`() {
        val breaks = lyricBreaks(listOf(line(0, 20_000), line(1_000, 2_000), line(21_000)))
        assertTrue(breaks.isEmpty())
    }

    @Test
    fun `a trailing adlib holds off the break`() {
        val withAdlib = line(0, 2_000).copy(adlib = LyricAdlib(emptyList(), endMs = 9_000))
        assertTrue(lyricBreaks(listOf(withAdlib, line(12_000))).isEmpty())
    }

    @Test
    fun `an open-ended timed adlib holds off the break`() {
        val withAdlib = line(0, 2_000).copy(adlib = LyricAdlib(listOf(AdlibSegment("ooh", startMs = 3_000))))
        assertTrue(lyricBreaks(listOf(withAdlib, line(12_000))).isEmpty())
    }

    @Test
    fun `unsynced lyrics have no breaks`() {
        assertTrue(lyricBreaks(listOf(LyricLine(0, "a", isSynced = false))).isEmpty())
        assertTrue(lyricBreaks(emptyList()).isEmpty())
    }

    @Test
    fun `break containment and progress`() {
        val gap = LyricBreak(0, 10_000, 20_000)
        assertFalse(9_999L in gap)
        assertTrue(10_000L in gap)
        assertFalse(20_000L in gap)
        assertEquals(0.5f, gap.progress(15_000), 0f)
        assertEquals(1f, gap.progress(25_000), 0f)
    }

    @Test
    fun `blank lrc lines end the line before them instead of rendering`() {
        val lines = LyricsStore.parseLrcText("[00:10.00]One\n[00:14.00]Two\n[00:22.00]\n[00:34.00]Three\n[00:40.00] \n[00:41.00]")
        assertEquals(listOf("One", "Two", "Three"), lines.map { it.text })
        assertEquals(listOf(null, 22_000L, 40_000L), lines.map { it.endMs })
        assertEquals(LyricBreak(1, 22_000, 34_000), lyricBreaks(lines).last())
    }

    @Test
    fun `enhanced lrc trailing stamp is the line end, not a word`() {
        val line = LyricsStore.parseLrcText("[00:02.00]<00:02.00>Hold <00:03.00>this <00:04.00>note<00:06.00>").single()
        assertEquals(listOf(TimedWord(2_000, "Hold"), TimedWord(3_000, "this"), TimedWord(4_000, "note", 6_000)), line.words)
        assertEquals(6_000L, line.endMs)
        val repeated = LyricsStore.parseLrcText("[00:02.00][00:20.00]<00:02.00>Hey <00:03.00>you<00:05.00>")
        assertEquals(listOf(5_000L, 23_000L), repeated.map { it.endMs })
        assertEquals(null, LyricsStore.parseLrcText("[00:02.00]<00:02.00>Hey <00:03.00>you").single().endMs)
    }

    @Test
    fun `jellyfin cue ends and blank lines become line ends`() = runTest {
        val api = FakeJellyfinApi(lyricsResponse = LyricsResponse(lyrics = listOf(
            LyricLineDto("Hold this", 20_000_000, listOf(LyricCueDto(0, 5, 20_000_000, 30_000_000), LyricCueDto(5, 9, 30_000_000, 60_000_000))),
            LyricLineDto("Sung", 100_000_000),
            LyricLineDto("", 140_000_000),
            LyricLineDto("Back", 340_000_000),
            LyricLineDto("Open end", 400_000_000, listOf(LyricCueDto(0, 4, 400_000_000, 410_000_000), LyricCueDto(5, 8, 410_000_000)))
        )))
        val lines = LyricsStore.fetch(api, "item")
        assertEquals(listOf("Hold this", "Sung", "Back", "Open end"), lines.map { it.text })
        assertEquals(listOf(6_000L, 14_000L, null, null), lines.map { it.endMs })
        assertEquals(listOf(LyricBreak(0, 6_000, 10_000), LyricBreak(1, 14_000, 34_000)), lyricBreaks(lines))
    }
}
