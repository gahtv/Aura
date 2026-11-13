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
    private val colors = ConcurrentHashMap<String, Int>()

    fun get(url: String): Int? = colors[url]
    fun put(url: String, color: Int) { colors[url] = color }
}

@Composable
fun rememberDynamicColor(imageUrl: String?): Color? {
    val context = androidx.compose.ui.platform.LocalContext.current
    var color by remember(imageUrl) {
        mutableStateOf(imageUrl?.let { u -> PaletteCache.get(u)?.let { Color(it) } })
    }

    LaunchedEffect(imageUrl) {
        if (imageUrl == null || color != null) return@LaunchedEffect
        runCatching {
            val request = ImageRequest.Builder(context)
                .data(imageUrl)
                .size(96) // tiny decode; palette needs no detail
                .allowHardware(false) // Palette needs readable pixels
                .build()
            val bitmap = context.imageLoader.execute(request).drawable?.let { d ->
                if (d is android.graphics.drawable.BitmapDrawable) d.bitmap else null
            } ?: return@LaunchedEffect
            val dominant = Palette.from(bitmap).maximumColorCount(8).generate()
                ?.getDominantColor(0xFF6B5A45.toInt()) ?: return@LaunchedEffect
            dominant
        }.onSuccess { c ->
            imageUrl?.let { PaletteCache.put(it, c) }
            color = Color(c)
        }
    }
    return color
}

/** Darkens a color for use as a background wash behind light text. */
fun darken(c: Color, factor: Float = 0.38f): Color =
    Color(c.red * factor, c.green * factor, c.blue * factor, 1f)
