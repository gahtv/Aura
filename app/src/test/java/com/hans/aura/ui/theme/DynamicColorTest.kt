package com.hans.aura.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.materialkolor.hct.Hct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicColorTest {
    @Test
    fun dominantChromaticHueSurvivesGrayBorder() {
        val red = 0xFFDD2222.toInt()
        val pixels = IntArray(1000) { if (it < 800) red else 0xFF888888.toInt() }
        val seed = Hct.fromInt(extractSeed(pixels))
        assertTrue(seed.hue in 10.0..40.0)
        assertTrue(seed.chroma > 40.0)
    }

    @Test
    fun achromaticAndEmptyArtworkUseAuraFallback() {
        assertEquals(AuraSeedArgb, extractSeed(intArrayOf()))
        assertEquals(AuraSeedArgb, extractSeed(IntArray(100) { 0xFFFFFFFF.toInt() }))
        assertEquals(AuraSeedArgb, extractSeed(IntArray(100) { 0xFF888888.toInt() }))
        assertEquals(AuraSeedArgb, extractSeed(IntArray(100) { 0xFF000000.toInt() }))
    }

    @Test
    fun schemesMaintainTextContrastAcrossSeedGrid() {
        val seeds = buildList {
            addAll(listOf(0xFFFFFFFE.toInt(), 0xFFFFFF00.toInt(), 0xFF000000.toInt(), AuraSeedArgb))
            for (r in 0..255 step 51) {
                for (g in 0..255 step 51) {
                    for (b in 0..255 step 51) add((0xFF shl 24) or (r shl 16) or (g shl 8) or b)
                }
            }
        }
        for (seed in seeds) {
            val scheme = dynamicColorScheme(seed)
            val tv = scheme.toTvColorScheme()
            val pairs = listOf(
                scheme.primary to scheme.onPrimary,
                scheme.primaryContainer to scheme.onPrimaryContainer,
                scheme.surface to scheme.onSurface,
                scheme.tertiaryContainer to scheme.onTertiaryContainer,
                tv.primary to tv.onPrimary,
                tv.primaryContainer to tv.onPrimaryContainer,
                tv.surface to tv.onSurface,
                tv.tertiaryContainer to tv.onTertiaryContainer
            )
            for ((background, foreground) in pairs) {
                assertTrue("Seed $seed: $background / $foreground", contrast(background, foreground) >= 4.5f)
            }
        }
    }

    @Test
    fun cacheStoresSeedsAndClearsAtSessionEnd() {
        PaletteCache.clear()
        try {
            assertEquals(null, PaletteCache.get("cover"))
            PaletteCache.put("cover", AuraSeedArgb)
            assertEquals(AuraSeedArgb, PaletteCache.get("cover"))
            assertEquals(1, PaletteCache.size)
            PaletteCache.clear()
            assertEquals(null, PaletteCache.get("cover"))
            assertEquals(0, PaletteCache.size)
        } finally {
            PaletteCache.clear()
        }
    }

    private fun contrast(a: Color, b: Color): Float =
        (maxOf(a.luminance(), b.luminance()) + 0.05f) /
            (minOf(a.luminance(), b.luminance()) + 0.05f)
}
