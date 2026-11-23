package com.hans.aura.player.controller

import androidx.media3.session.MediaController
import androidx.media3.common.Player
import com.hans.aura.data.JellyfinClient
import com.hans.aura.data.Prefs
import com.hans.aura.data.Session
import com.hans.aura.data.toTracks
import com.hans.aura.util.AuraLog
import com.hans.aura.util.AuraTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * "Keep the music going": when the queue reaches its last item and would END,
 * append an Instant Mix seeded from that track (minus anything already queued,
 * max 20).
 *
 * Never fires when the user pressed stop (that lands in STATE_IDLE, not ENDED),
 * when repeat is on (a repeat handles the end itself), when the preference is
 * off, or twice for the same seed.
 */
class AutoplayPolicy(
    private val jellyfin: JellyfinClient,
    private val sessionProvider: () -> Session?,
    private val registry: QueueRegistry,
    private val scope: CoroutineScope,
    private val postMain: (block: () -> Unit) -> Unit
) {
    /** Seed id of the last autoplay continuation, so one track end appends once. */
    private var lastAutoplaySeed: String? = null
    private var autoplayInFlight = false

    /** A new queue means a new autoplay chain. */
    fun reset() {
        lastAutoplaySeed = null
        autoplayInFlight = false
    }

    fun maybeContinue(c: MediaController) {
        fun skip(why: String) {
            AuraLog.d(AuraTag.AUTOPLAY, "skip: $why")
        }
        if (!Prefs.autoplayMusic) return skip("pref off")
        if (c.repeatMode != Player.REPEAT_MODE_OFF) return skip("repeat on")
        if (c.mediaItemCount == 0) return skip("empty queue")
        // Deliberately NOT gated on playWhenReady: a player that has just
        // reached the end of its last item can report playWhenReady=false even
        // though the user never asked to stop, and gating on it meant the queue
        // simply died (seen on the emulator). Ending at the last item IS the
        // signal; a user-initiated stop does not produce STATE_ENDED.
        if (autoplayInFlight) return skip("in flight")
        val seed = c.getMediaItemAt(c.mediaItemCount - 1).mediaId
        if (seed == lastAutoplaySeed) return skip("same seed $seed")
        val session = sessionProvider() ?: return
        val serverUrl = session.serverUrl
        // Read the player on the main thread; the coroutine below must not.
        val known = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }.toSet()

        lastAutoplaySeed = seed
        autoplayInFlight = true
        scope.launch {
            val added = runCatching {
                val resp = jellyfin.api.getInstantMix(seed, session.userId, limit = 40)
                resp.items
                    .filter { it.id !in known && it.type != "MusicAlbum" }
                    .distinctBy { it.id }
                    .take(20)
                    .toTracks()
            }.onFailure {
                AuraLog.w(AuraTag.AUTOPLAY, "instant mix failed: ${it.message}")
            }.getOrDefault(emptyList())

            postMain {
                autoplayInFlight = false
                if (added.isEmpty()) {
                    // Allow a later end on the same track to try again.
                    if (lastAutoplaySeed == seed) lastAutoplaySeed = null
                    AuraLog.d(AuraTag.AUTOPLAY, "appended=0 seed=$seed")
                    return@postMain
                }
                registry.putAll(added)
                val atEnd = c.mediaItemCount
                c.addMediaItems(added.map { toMediaItem(it, serverUrl) })
                registry.appendToQueue(added.map { it.id })
                // ENDED + appended items: step onto the first new track and
                // re-prepare, otherwise the player sits on the finished last one.
                c.seekToDefaultPosition(atEnd)
                c.prepare()
                c.playWhenReady = true
                AuraLog.d(AuraTag.AUTOPLAY, "appended=${added.size} seed=$seed")
            }
        }
    }
}
