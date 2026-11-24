package com.hans.aura.di

import android.content.Context
import androidx.room.Room
import com.hans.aura.data.CacheDatabase
import com.hans.aura.data.CacheDisk
import com.hans.aura.data.AtmosCatalog
import com.hans.aura.BuildConfig
import com.hans.aura.data.AtmosFilter
import com.hans.aura.data.JellyfinClient
import com.hans.aura.data.LibraryCache
import com.hans.aura.data.MediaRepository
import com.hans.aura.data.MusicSource
import com.hans.aura.data.MusicSources
import com.hans.aura.data.Prefs
import com.hans.aura.remote.MirrorServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Scope for mutations a screen starts but must not own.
 *
 * Playlist edits are launched here instead of a destination's `viewModelScope`:
 * Navigation 3 clears a popped entry's ViewModelStore after its exit
 * transition, and cancelling [MediaRepository.removeFromPlaylist] mid-flight
 * loses the write (cancelled during the pre-read), or — worse — loses the
 * cache eviction after the server already applied the edit, so the next open
 * still shows the removed track.
 *
 * One scope per signed-in session: [reset] cancels everything still in flight
 * and starts a fresh scope, so a mutation started for the old profile can
 * never land after a profile switch or sign-out.
 */
class SessionMutations {
    private val lock = Any()
    private var supervisor = SupervisorJob()
    private var current = CoroutineScope(supervisor + Dispatchers.Default)

    /** Scope owned by the current session; cancelled by [reset]. */
    val scope: CoroutineScope get() = synchronized(lock) { current }

    /** Cancels in-flight mutations and starts a fresh session scope. */
    fun reset() {
        synchronized(lock) {
            supervisor.cancel()
            supervisor = SupervisorJob()
            current = CoroutineScope(supervisor + Dispatchers.Default)
        }
    }
}

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
 *  7. [MusicSource]  — the library backend this build targets, created by [BackendFactory].
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext
    val cacheDatabase = Room.databaseBuilder(appContext, CacheDatabase::class.java, "aura-cache.db").build()
    private val cacheDisk = CacheDisk(cacheDatabase.cacheDao())

    /** Retrofit-backed Jellyfin client (auth store, api, device id). */
    val jellyfin: JellyfinClient

    /** Browse/query facade ViewModels and screens should prefer. */
    val media: MediaRepository

    /**
     * Session-owned scope for mutations that must outlive the screen that
     * started them (playlist edits). See [SessionMutations].
     */
    val mutations = SessionMutations()

    /**
     * The library backend this build browses and streams from.
     *
     * Selected at build time by the active product flavor via [BackendFactory] and
     * exposed here so a different backend can be swapped in without touching a
     * single caller: screens keep reading [media], and the player asks this for
     * stream URLs.
     */
    val source: MusicSource

    init {
        Prefs.init(appContext)
        LibraryCache.init(appContext)
        LibraryCache.install(cacheDisk)
        AtmosCatalog.install(cacheDisk)
        AtmosFilter.load()
        MirrorServer.init(appContext)

        jellyfin = JellyfinClient(appContext)
        media = MediaRepository(jellyfin, { jellyfin.auth.session })
        source = BackendFactory.create(jellyfin, media)
        // Hand the same instance to the call sites that live outside this graph
        // (the queue builder and the playback service).
        MusicSources.install(source)
        android.util.Log.i("AURA_SRC", "source=${source.id} backend=${BuildConfig.MUSIC_SOURCE}")
        // Proof for a device session that the gate is where it should be: the
        // Jellyfin-only session reporting / remote-control side is on or off.
        android.util.Log.i(
            "AURA_REPORT",
            "play-state reporting ${if (source.reportsPlayState) "on" else "off"} (source=${source.id})"
        )
    }
}
