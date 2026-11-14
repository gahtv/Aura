package com.hans.aura.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import java.util.concurrent.ConcurrentHashMap

/**
 * Extracts a dominant color from an image URL for dynamic per-album theming.
 *
 * Performance matters on TV hardware: the bitmap is requested at 96px
 * (a full-size software decode here used to stall the decode pipeline),
 * a single shared Coil loader handles it, and results are cached process-wide
 * so re-entering the same album is instant.
 */
object PaletteCache {
    // Value packs (dominant, secondary) as two ARGB ints in a Long so both
    // halves of the palette survive a cache hit; secondary sits in the high 32.
    private val colors = ConcurrentHashMap<String, Long>()

    fun get(url: String): Long? = colors[url]
    fun put(url: String, packed: Long) { colors[url] = packed }
}

@Composable
fun rememberDynamicColor(imageUrl: String?): Color? =
    rememberDynamicColors(imageUrl)?.primary

data class DynamicColors(val primary: Color, val secondary: Color)

/**
 * Two-color dynamic palette: the dominant color darkens into the background
 * wash; a second, distinct, sufficiently light swatch from the same art
 * becomes the accent/highlight color so it reads against the dark background.
 */
@Composable
fun rememberDynamicColors(imageUrl: String?): DynamicColors? {
    val context = androidx.compose.ui.platform.LocalContext.current
    var pair by remember(imageUrl) {
        mutableStateOf(imageUrl?.let { u -> PaletteCache.get(u)?.let { toColors(it) } })
    }

    LaunchedEffect(imageUrl) {
        if (imageUrl == null || pair != null) return@LaunchedEffect
        runCatching {
            val request = ImageRequest.Builder(context)
                .data(imageUrl)
                .size(96) // tiny decode; palette needs no detail
                .allowHardware(false) // Palette needs readable pixels
                .build()
            val bitmap = context.imageLoader.execute(request).drawable?.let { d ->
                if (d is android.graphics.drawable.BitmapDrawable) d.bitmap else null
            } ?: return@LaunchedEffect
            val palette = Palette.from(bitmap).maximumColorCount(16).generate() ?: return@LaunchedEffect
            val dominant = palette.getDominantColor(0xFF6B5A45.toInt())
            dominant to pickSecondary(palette, dominant)
        }.onSuccess { (dom, sec) ->
            val packed = (dom.toLong() and 0xFFFFFFFFL) or ((sec.toLong() and 0xFFFFFFFFL) shl 32)
            imageUrl?.let { PaletteCache.put(it, packed) }
            pair = toColors(packed)
        }
    }
    return pair
}

/** Secondary = most saturated swatch clearly distinct from the dominant one. */
private fun pickSecondary(palette: Palette, dominant: Int): Int {
    val domHsv = FloatArray(3)
    android.graphics.Color.colorToHSV(dominant, domHsv)
    var best = -1
    var bestScore = -1f
    for (swatch in palette.swatches) {
        val c = swatch.rgb
        if (c == dominant) continue
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(c, hsv)
        // 30 degrees apart in hue, or clearly different lightness, counts as distinct
        val hueGap = minOf(kotlin.math.abs(hsv[0] - domHsv[0]), 360f - kotlin.math.abs(hsv[0] - domHsv[0]))
        val distinct = hueGap > 30f || kotlin.math.abs(hsv[2] - domHsv[2]) > 0.25f
        val score = if (distinct) hsv[1] * (1f - kotlin.math.abs(hsv[2] - 0.6f)) else -1f
        if (score > bestScore) { bestScore = score; best = c }
    }
    if (best < 0) {
        // No distinct swatch: derive one from the dominant hue, shifted and
        // brightened so it still reads as a highlight.
        val hsv = floatArrayOf(domHsv[0] + 40f, domHsv[1], 0.75f)
        best = android.graphics.Color.HSVToColor(hsv)
    }
    return best
}

/** Packs the two raw palette ints into the cache; unpacks for consumption. */
private fun toColors(packed: Long): DynamicColors {
    val dom = (packed and 0xFFFFFFFFL).toInt()
    val sec = ((packed ushr 32) and 0xFFFFFFFFL).toInt()
    return DynamicColors(darken(Color(dom), 0.30f), ensureReadable(Color(sec)))
}

/**
 * Lightens a highlight color until it has enough contrast on a very dark
 * background (the darkened dominant wash).
 */
private fun ensureReadable(c: Color): Color {
    val lum = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue
    if (lum >= 0.40f) return c
    val t = (0.40f - lum) / (1f - lum).coerceAtLeast(0.001f)
    return Color(
        c.red + (1f - c.red) * t,
        c.green + (1f - c.green) * t,
        c.blue + (1f - c.blue) * t,
        1f
    )
}

/** Darkens a color for use as a background wash behind light text. */
fun darken(c: Color, factor: Float = 0.38f): Color =
    Color(c.red * factor, c.green * factor, c.blue * factor, 1f)
