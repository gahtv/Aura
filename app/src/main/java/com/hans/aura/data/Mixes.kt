package com.hans.aura.data

import android.util.Log
import com.hans.aura.model.Album
import com.hans.aura.model.Artist
import com.hans.aura.model.MediaItem
import com.hans.aura.model.Track
import com.hans.aura.ui.player.PlayerConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Instant Mix ("radio") — discovery from a seed item. */
object Mixes {

    /** Which InstantMix path a seed goes down. */
    enum class Kind(val wire: String) {
        TRACK("track"),
        ALBUM("album"),
        ARTIST("artist"),
        GENRE("genre")
    }

    /** ~50 tracks: long enough to feel like radio, one request. */
    const val MIX_SIZE = 50

    private const val TAG = "AURA_MIX"

    /** Fetch the mix for one seed without playing it. */
    suspend fun fetch(media: MediaRepository, kind: Kind, seed: MediaItem): List<Track> = when (kind) {
        Kind.TRACK -> media.trackMix(seed as Track, MIX_SIZE)
        Kind.ALBUM -> media.albumMix(seed as Album, MIX_SIZE)
        Kind.ARTIST -> media.artistMix(seed as Artist, MIX_SIZE)
        Kind.GENRE -> media.genreMix(seed.name, MIX_SIZE)
    }

    /** Fetch ~50 tracks for [seed] and start playing them on the main thread. */
    suspend fun playMix(
        media: MediaRepository,
        player: PlayerConnection,
        kind: Kind,
        seed: MediaItem
    ) {
        val items = runCatching { fetch(media, kind, seed) }.getOrElse { error ->
            Log.w(TAG, "fetch failed kind=${kind.wire} seed=${seed.id}: ${error.message}")
            emptyList()
        }
        Log.d(TAG, "kind=${kind.wire} seed=${seed.id} count=${items.size}")
        if (items.isEmpty()) return
        withContext(Dispatchers.Main) { player.playQueue(items, 0, resume = false) }
    }
}
