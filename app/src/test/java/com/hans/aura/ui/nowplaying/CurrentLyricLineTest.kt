package com.hans.aura.ui.nowplaying

import com.hans.aura.data.LyricsStore
import org.junit.Assert.assertEquals
import org.junit.Test

class CurrentLyricLineTest {
    @Test
    fun `unsynced lyrics have no current line`() {
        val plain = LyricsStore.parseLrcText("First line\nSecond line")
        assertEquals(-1, computeCurrentLine(plain, 0L))
        assertEquals(-1, computeCurrentLine(plain, 90_000L))
    }

    @Test
    fun `synced lyrics follow the clock`() {
        val synced = LyricsStore.parseLrcText("[00:05]First\n[00:10]Second")
        assertEquals(-1, computeCurrentLine(synced, 1_000L))
        assertEquals(0, computeCurrentLine(synced, 5_000L))
        assertEquals(1, computeCurrentLine(synced, 12_000L))
    }
}
