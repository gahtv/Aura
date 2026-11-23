package com.hans.aura.playback

import com.hans.aura.player.controller.PlaybackAnchor
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackAnchorTest {
    @Test fun playingAdvancesFromAnchor() {
        assertEquals(1500L, PlaybackAnchor(1000, 100, playing = true).positionAt(600))
    }

    @Test fun pausedStaysStill() {
        assertEquals(1000L, PlaybackAnchor(1000, 100).positionAt(600))
    }

    @Test fun speedScalesElapsedTime() {
        assertEquals(1750L, PlaybackAnchor(1000, 100, 1.5f, true).positionAt(600))
    }

    @Test fun clampsToKnownDurationAndZero() {
        assertEquals(1200L, PlaybackAnchor(1000, 100, playing = true, durationMs = 1200).positionAt(600))
        assertEquals(0L, PlaybackAnchor(-100).positionAt(600))
        assertEquals(0L, PlaybackAnchor().positionAt(600))
    }

    @Test fun seekAndPauseReplaceAnchor() {
        val original = PlaybackAnchor(1000, 100, playing = true)
        val sought = original.copy(positionMs = 5000, anchorUptimeMs = 600)
        assertEquals(5200L, sought.positionAt(800))
        val paused = sought.copy(positionMs = sought.positionAt(800), anchorUptimeMs = 800, playing = false)
        assertEquals(5200L, paused.positionAt(2000))
    }
}
