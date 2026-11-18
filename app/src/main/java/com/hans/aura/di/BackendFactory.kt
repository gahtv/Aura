package com.hans.aura.di

import com.hans.aura.data.JellyfinClient
import com.hans.aura.data.JellyfinSource
import com.hans.aura.data.MediaRepository
import com.hans.aura.data.MusicSource

/**
 * Flavor-provided [MusicSource] factory for the Jellyfin build.
 */
object BackendFactory {

    fun create(jellyfin: JellyfinClient, media: MediaRepository): MusicSource =
        JellyfinSource(jellyfin, media)
}
