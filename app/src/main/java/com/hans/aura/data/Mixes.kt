package com.hans.aura.data

import android.util.Log
import com.hans.aura.ui.player.PlayerConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Instant Mix ("radio") — discovery from a seed item.
 *
 * Endpoints, verified against references/jellyfin-openapi-stable.json (12.4.0):
 *   GET /Items/{itemId}/InstantMix        tracks / albums / anything with an id
 *   GET /Artists/{itemId}/InstantMix      an artist seed
 *   GET /Songs/{itemId}/InstantMix        song seed (same shape as /Items)
 *   GET /MusicGenres/{name}/InstantMix    the GENRE NAME goes in the path
 * All take userId, limit, fields. Each returns QueryResult<BaseItemDto>, so the
 * shared ItemsResponse model is the right deserialisation target.
 *
 * There is no queue-label channel in PlayerConnection (queue/queueIndex are
 * plain item + index flows owned by the player branch), so a mix is NOT
 * labelled "Radio · <seed>" anywhere in the UI. The AURA_MIX log line is the
 * only identity a mix has; see the report.
 */
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

    suspend fun trackMix(api: JellyfinApi, userId: String, track: JellyfinItem): List<JellyfinItem> =
        api.instantMix(track.id, userId = userId, limit = MIX_SIZE).items

    suspend fun albumMix(api: JellyfinApi, userId: String, album: JellyfinItem): List<JellyfinItem> =
        api.instantMix(album.id, userId = userId, limit = MIX_SIZE).items

    suspend fun artistMix(api: JellyfinApi, userId: String, artist: JellyfinItem): List<JellyfinItem> =
        api.artistInstantMix(artist.id, userId = userId, limit = MIX_SIZE).items

    /** [name] is the genre's display name — the endpoint takes a name, not an id. */
    suspend fun genreMix(api: JellyfinApi, userId: String, name: String): List<JellyfinItem> =
        api.genreInstantMix(name, userId = userId, limit = MIX_SIZE).items

    /** Fetch the mix for one seed without playing it. */
    suspend fun fetch(
        api: JellyfinApi,
        userId: String,
        kind: Kind,
        seed: JellyfinItem
    ): List<JellyfinItem> {
        val items = when (kind) {
            Kind.TRACK -> trackMix(api, userId, seed)
            Kind.ALBUM -> albumMix(api, userId, seed)
            Kind.ARTIST -> artistMix(api, userId, seed)
            Kind.GENRE -> genreMix(api, userId, seed.name)
        }
        // InstantMix occasionally mixes in non-audio entries for odd seeds;
        // only playable tracks belong in the queue.
        return items.filter { it.type.isEmpty() || it.type == "Audio" }
    }

    /**
     * The one entry point: fetch ~50 tracks for [seed] and start playing them.
     * Safe to call from any dispatcher — playback is marshalled onto the main
     * thread (Media3 throws "accessed on the wrong thread" otherwise).
     *
     * Verifiable headlessly: `adb logcat -s AURA_MIX` prints
     * `kind=<track|album|artist|genre> seed=<id> count=<n>`.
     */
    suspend fun playMix(
        api: JellyfinApi,
        userId: String,
        player: PlayerConnection,
        kind: Kind,
        seed: JellyfinItem
    ) {
        val items = runCatching { fetch(api, userId, kind, seed) }.getOrElse { error ->
            Log.w(TAG, "fetch failed kind=${kind.wire} seed=${seed.id}: ${error.message}")
            emptyList()
        }
        Log.d(TAG, "kind=${kind.wire} seed=${seed.id} count=${items.size}")
        if (items.isEmpty()) return
        withContext(Dispatchers.Main) { player.playQueue(items, 0) }
    }
}
