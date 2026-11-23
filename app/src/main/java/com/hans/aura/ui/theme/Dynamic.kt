package com.hans.aura.ui.theme

import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import coil.imageLoader
import coil.request.ImageRequest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object PaletteCache {
    private val seeds = ConcurrentHashMap<String, Int>()

    fun get(url: String): Int? = seeds[url]
    fun put(url: String, seed: Int) { seeds[url] = seed }
    val size: Int get() = seeds.size
    fun clear() = seeds.clear()
}

@Composable
fun rememberArtworkSeed(imageUrl: String?): Int? {
    val context = androidx.compose.ui.platform.LocalContext.current
    var seed by remember(imageUrl) { mutableStateOf(imageUrl?.let(PaletteCache::get)) }
    LaunchedEffect(imageUrl) {
        if (imageUrl == null || seed != null) return@LaunchedEffect
        try {
            val request = ImageRequest.Builder(context)
                .data(imageUrl)
                .size(96)
                .allowHardware(false)
                .build()
            val bitmap = (context.imageLoader.execute(request).drawable as? BitmapDrawable)?.bitmap
                ?: return@LaunchedEffect
            val extracted = withContext(Dispatchers.Default) {
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                extractSeed(pixels)
            }
            PaletteCache.put(imageUrl, extracted)
            seed = extracted
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            seed = null
        }
    }
    return seed
}

data class DynamicColors(
    val primary: Color,
    val secondary: Color,
    val surfaceContainer: Color,
    val surfaceContainerHigh: Color,
    val onAccent: Color,
    val tertiary: Color
)

@Composable
fun rememberDynamicColors(imageUrl: String?): DynamicColors? {
    val seed = rememberArtworkSeed(imageUrl)
    return remember(seed) {
        seed?.let {
            val scheme = dynamicColorScheme(it)
            DynamicColors(
                primary = scheme.surfaceContainerHigh,
                secondary = scheme.primary,
                surfaceContainer = scheme.surfaceContainer,
                surfaceContainerHigh = scheme.surfaceContainerHigh,
                onAccent = scheme.onPrimary,
                tertiary = scheme.tertiary
            )
        }
    }
}

@Composable
fun rememberDynamicColor(imageUrl: String?): Color? = rememberDynamicColors(imageUrl)?.primary

fun darken(c: Color, factor: Float = 0.38f): Color =
    Color(c.red * factor, c.green * factor, c.blue * factor, 1f)

fun readableOn(background: Color): Color {
    val bgLum = background.luminance()
    val darkInk = AuraColors.OnAccent
    val darkContrast = (maxOf(bgLum, darkInk.luminance()) + 0.05f) /
        (minOf(bgLum, darkInk.luminance()) + 0.05f)
    return if (darkContrast >= 4.5f) darkInk else Color.White
}

fun deriveTertiary(accent: Color): Color = dynamicColorScheme(accent.toArgb()).tertiary
