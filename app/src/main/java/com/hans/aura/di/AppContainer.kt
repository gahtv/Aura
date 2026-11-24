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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

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
 *
 * It also publishes playlist edit lifecycles. A playlist reopened while an
 * earlier edit is still in flight must not trust `LibraryCache` (the eviction
 * has not happened yet), and the screen showing the old list must refetch once
 * the edit completes. [beginPlaylistEdit] / [finishPlaylistEdit] track that,
 * [playlistEditPending] answers the reopen question synchronously, and
 * [observePlaylistChanges] is the completion stream.
 */
class SessionMutations {
    private val lock = Any()
    private var supervisor = SupervisorJob()
    private var current = CoroutineScope(supervisor + Dispatchers.Default)

    /**
     * Session generation, bumped by [reset]. Tokens handed out by
     * [beginPlaylistEdit] carry the generation they started in, so a
     * cancelled old-profile mutation whose `finally` runs late can never
     * clear a new session's pending entry or publish a change event into it.
     */
    private var generation = 0L

    /** In-flight edit count per playlist id. */
    private val pending = mutableMapOf<String, Int>()

    private val _playlistChanges = MutableSharedFlow<String>(extraBufferCapacity = 16)

    /** Scope owned by the current session; cancelled by [reset]. */
    val scope: CoroutineScope get() = synchronized(lock) { current }

    /**
     * Marks an edit of [playlistId] as in flight and returns the token that
     * [finishPlaylistEdit] must pass back. Call before launching the write.
     */
    fun beginPlaylistEdit(playlistId: String): Long = synchronized(lock) {
        pending[playlistId] = (pending[playlistId] ?: 0) + 1
        generation
    }

    /**
     * Ends the edit started with [token]. The playlist change event is
     * published once the last overlapping edit completes; a token from an
     * earlier session (or an unknown/cleared entry) is ignored.
     */
    fun finishPlaylistEdit(playlistId: String, token: Long) {
        var completed = false
        synchronized(lock) {
            val remaining = pending[playlistId]
            if (token == generation && remaining != null) {
                if (remaining > 1) {
                    pending[playlistId] = remaining - 1
                } else {
                    pending.remove(playlistId)
                    completed = true
                }
            }
        }
        if (completed) _playlistChanges.tryEmit(playlistId)
    }

    /** True while an edit of [playlistId] is still in flight in this session. */
    fun playlistEditPending(playlistId: String): Boolean = synchronized(lock) {
        (pending[playlistId] ?: 0) > 0
    }

    /** Playlist ids whose last in-flight edit completed (no replay, no stale sessions). */
    fun observePlaylistChanges(): Flow<String> = _playlistChanges.asSharedFlow()

    /** Cancels in-flight mutations and starts a fresh session scope. */
    fun reset() {
        synchronized(lock) {
            supervisor.cancel()
            supervisor = SupervisorJob()
            current = CoroutineScope(supervisor + Dispatchers.Default)
            // Pending edits belong to the old session; their late finish()
            // calls now fail the generation check instead of notifying the
            // new session.
            generation++
            pending.clear()
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
