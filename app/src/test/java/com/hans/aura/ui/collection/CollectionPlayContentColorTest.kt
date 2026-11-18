package com.hans.aura.ui.collection

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.hans.aura.ui.theme.Accent
import com.hans.aura.ui.theme.AuraColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionPlayContentColorTest {
    @Test
    fun `amber and light accents use the on-accent token`() {
        assertEquals(AuraColors.OnAccent, collectionPlayContentColor(Accent))
        assertEquals(AuraColors.OnAccent, collectionPlayContentColor(Color.White))
    }

    @Test
    fun `dark accents use white ink`() {
        assertEquals(Color.White, collectionPlayContentColor(Color(0xFF101B45)))
        assertEquals(Color.White, collectionPlayContentColor(Color.Black))
    }

    @Test
    fun `ink maintains readable contrast across accent hues and brightnesses`() {
        for (red in 0..255 step 15) {
            for (green in 0..255 step 15) {
                for (blue in 0..255 step 15) {
                    val accent = Color(red, green, blue)
                    val ink = collectionPlayContentColor(accent)
                    val light = maxOf(accent.luminance(), ink.luminance())
                    val dark = minOf(accent.luminance(), ink.luminance())
                    assertTrue("Unreadable ink on $accent", (light + 0.05f) / (dark + 0.05f) >= 4.5f)
                }
            }
        }
    }
}
