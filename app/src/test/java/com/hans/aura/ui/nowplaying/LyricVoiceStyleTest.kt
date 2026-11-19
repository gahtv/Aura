package com.hans.aura.ui.nowplaying

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import com.hans.aura.data.LyricVoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricVoiceStyleTest {
    private val highlight = Color(0xffffaa33)

    @Test
    fun `single voice stays centered with original accent`() {
        (LyricVoice.Kind.entries.map { LyricVoice(it.name, it) } + null).forEach {
            assertEquals(TextAlign.Center to highlight, lyricVoiceStyle(it, false, highlight))
        }
    }

    @Test
    fun `duet aligns each singer and uses a bright distinct secondary tint`() {
        assertEquals(TextAlign.Start to highlight,
            lyricVoiceStyle(LyricVoice("v1", LyricVoice.Kind.PRIMARY), true, highlight))
        val (align, tint) = lyricVoiceStyle(LyricVoice("v2", LyricVoice.Kind.SECONDARY), true, highlight)
        assertEquals(TextAlign.End, align)
        assertNotEquals(highlight, tint)
        assertTrue(maxOf(tint.red, tint.green, tint.blue) >= 0.85f)
        assertEquals(highlight.alpha, tint.alpha)
        assertEquals(TextAlign.Center to highlight,
            lyricVoiceStyle(LyricVoice("v1000", LyricVoice.Kind.GROUP), true, highlight))
        assertEquals(TextAlign.Center to highlight, lyricVoiceStyle(null, true, highlight))
    }
}
