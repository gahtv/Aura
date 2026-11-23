package com.hans.aura.ui.collection

import androidx.compose.ui.graphics.luminance
import com.hans.aura.ui.theme.Accent
import com.hans.aura.ui.theme.AuraColorScheme
import com.hans.aura.ui.theme.AuraColors
import com.hans.aura.ui.theme.dynamicColorScheme
import com.hans.aura.ui.theme.toTvColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionPlayContentColorTest {
    @Test
    fun `static play button retains amber and its existing ink`() {
        assertEquals(Accent, AuraColorScheme.primary)
        assertEquals(AuraColors.OnAccent, AuraColorScheme.onPrimary)
        assertReadable(AuraColorScheme)
    }

    @Test
    fun `play button role pair maintains contrast across seed hues and brightnesses`() {
        for (red in 0..255 step 15) {
            for (green in 0..255 step 15) {
                for (blue in 0..255 step 15) {
                    val seed = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
                    val generated = dynamicColorScheme(seed)
                    val colors = generated.toTvColorScheme()
                    assertEquals(generated.primary, colors.primary)
                    assertEquals(generated.onPrimary, colors.onPrimary)
                    assertReadable(colors)
                }
            }
        }
    }

    private fun assertReadable(colors: androidx.tv.material3.ColorScheme) {
        val light = maxOf(colors.primary.luminance(), colors.onPrimary.luminance())
        val dark = minOf(colors.primary.luminance(), colors.onPrimary.luminance())
        assertTrue("Unreadable primary/onPrimary", (light + 0.05f) / (dark + 0.05f) >= 4.5f)
    }
}
