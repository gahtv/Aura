package com.hans.aura

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.BitmapFactoryDecoder
import coil.memory.MemoryCache
import com.hans.aura.data.JellyfinClient

class AuraApp : Application(), ImageLoaderFactory {
    lateinit var jellyfin: JellyfinClient
        private set

    override fun onCreate() {
        super.onCreate()
        jellyfin = JellyfinClient(this)
    }

    /**
     * TV devices have weak CPUs: decode artwork as RGB_565 (half the memory
     * bandwidth of ARGB_8888, visually fine for album art), cap the memory
     * cache, and skip crossfades that force double-draw during scrolling.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components {
                add(BitmapFactoryDecoder.Factory())
            }
            .bitmapConfig(android.graphics.Bitmap.Config.RGB_565)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.20)
                    .build()
            }
            .crossfade(false)
            .build()
}
