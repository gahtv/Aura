package com.hans.aura.ui.nowplaying

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextAlign
import com.hans.aura.data.LyricVoice
import com.hans.aura.ui.theme.dynamicColorScheme
import com.hans.aura.ui.theme.toTvColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricVoiceStyleTest {
    private val colors = dynamicColorScheme(0xFFFFAA33.toInt()).toTvColorScheme()

    @Test
    fun `single voice stays centered with primary role`() {
        (LyricVoice.Kind.entries.map { LyricVoice(it.name, it) } + null).forEach {
            assertEquals(TextAlign.Center to colors.primary, lyricVoiceStyle(it, false, colors))
        }
    }

    @Test
    fun `duet uses primary and tertiary roles and keeps group and unknown voices centered`() {
        assertEquals(TextAlign.Start to colors.primary,
            lyricVoiceStyle(LyricVoice("v1", LyricVoice.Kind.PRIMARY), true, colors))
        assertEquals(TextAlign.End to colors.tertiary,
            lyricVoiceStyle(LyricVoice("v2", LyricVoice.Kind.SECONDARY), true, colors))
        assertEquals(TextAlign.Center to colors.primary,
            lyricVoiceStyle(LyricVoice("v1000", LyricVoice.Kind.GROUP), true, colors))
        assertEquals(TextAlign.Center to colors.primary, lyricVoiceStyle(null, true, colors))
    }

    @Test
    fun `duet voices remain distinct and readable across seed hues and brightnesses`() {
        val seeds = listOf(0xFF000000.toInt(), 0xFFFFFFFE.toInt(), 0xFFFFFF00.toInt()) +
            (0..330 step 30).map { Color.hsv(it.toFloat(), 1f, 1f) }
                .map { it.toArgb() }
        for (seed in seeds) {
            val scheme = dynamicColorScheme(seed).toTvColorScheme()
            val primary = lyricVoiceStyle(LyricVoice("v1", LyricVoice.Kind.PRIMARY), true, scheme).second
            val secondary = lyricVoiceStyle(LyricVoice("v2", LyricVoice.Kind.SECONDARY), true, scheme).second
            assertNotEquals(primary, secondary)
            assertEquals(primary.alpha, secondary.alpha)
            assertTrue(contrast(primary, scheme.surface) >= 4.5f)
            assertTrue(contrast(secondary, scheme.surface) >= 4.5f)
            assertTrue(contrast(primary, scheme.secondaryContainer) >= 4.5f)
            assertTrue(contrast(secondary, scheme.secondaryContainer) >= 4.5f)
        }
    }

    private fun contrast(a: Color, b: Color): Float =
        (maxOf(a.luminance(), b.luminance()) + 0.05f) /
            (minOf(a.luminance(), b.luminance()) + 0.05f)
}
