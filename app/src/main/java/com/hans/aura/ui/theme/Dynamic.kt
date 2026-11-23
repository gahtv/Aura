package com.hans.aura.ui.theme

import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import coil.imageLoader
import coil.request.ImageRequest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ArtworkSeedCache {
    private val seeds = ConcurrentHashMap<String, Int>()

    fun get(url: String): Int? = seeds[url]
    fun put(url: String, seed: Int) { seeds[url] = seed }
    val size: Int get() = seeds.size
    fun clear() = seeds.clear()
}

@Composable
fun rememberArtworkSeed(imageUrl: String?): Int? {
    val context = androidx.compose.ui.platform.LocalContext.current
    var seed by remember(imageUrl) { mutableStateOf(imageUrl?.let(ArtworkSeedCache::get)) }
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
            ArtworkSeedCache.put(imageUrl, extracted)
            seed = extracted
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            seed = null
        }
    }
    return seed
}
