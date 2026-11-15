package com.hans.aura.di

import android.content.Context
import com.hans.aura.data.AtmosFilter
import com.hans.aura.data.JellyfinClient
import com.hans.aura.data.LibraryCache
import com.hans.aura.data.MediaRepository
import com.hans.aura.data.Prefs
import com.hans.aura.remote.MirrorServer

/**
 * Manual dependency container — the single place the app's long-lived stores are
 * built, in a deterministic order.
 *
 * Deliberately not Hilt/Koin: Aura has exactly one graph, so a 40-line
 * constructor is easier to follow (and to keep under R8) than an annotation
 * processor. The order matters and is asserted by the store contracts:
 *
 *  1. [Prefs]        — file-backed prefs; every other store reads it.
 *  2. [LibraryCache] — TTL cache; keys are suffixed by the library selection
 *                      that Prefs owns, so Prefs must be initialised first.
 *  3. [AtmosFilter]  — mirrors Prefs.atmosOnly as Compose state ("after Prefs").
 *  4. [MirrorServer] — device name + stored pairing token (needs the app ctx).
 *  5. [JellyfinClient] — session store + Retrofit; needs prefs to be readable.
 *  6. [MediaRepository] — main-safe facade over the browse layer, over (5).
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    /** Retrofit-backed Jellyfin client (auth store, api, device id). */
    val jellyfin: JellyfinClient

    /** Browse/query facade ViewModels and screens should prefer. */
    val media: MediaRepository

    init {
        Prefs.init(appContext)
        LibraryCache.init(appContext)
        AtmosFilter.load()
        MirrorServer.init(appContext)

        jellyfin = JellyfinClient(appContext)
        media = MediaRepository(jellyfin, { jellyfin.auth.session })
    }
}
