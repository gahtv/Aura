package com.hans.aura.player.controller

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.hans.aura.data.AuthStore
import com.hans.aura.data.JellyfinItem
import com.hans.aura.data.MusicSource
import com.hans.aura.data.MusicSources
import kotlin.math.roundToInt

/**
 * The in-process knowledge of WHAT is queued.
 *
 * Media3 1.6 MediaItems carry only mediaId + URI (no extras payload), so the
 * full [JellyfinItem] for every queued mediaId is kept here: UI metadata, Atmos
 * badges, lyrics and playback reports all resolve through [get].
 *
 * Also owns the queue ID snapshot the "re-tap the playing row opens Now Playing
 * instead of restarting it" rule reads, plus [normalOrderIds] (the pre-smart-
 * shuffle order, so "Normal order" can restore it).
 *
 * The snapshot is mutated by every queue mutation Aura performs; [sameQueue]
 * additionally verifies it against the real player order, so a mutation Aura did
 * not make (remote Play Next/Last, which only touches the player) falls back to
 * the strict comparison instead of being masked by a stale snapshot.
 */
class QueueRegistry {

    private val lock = Any()
    private val items = LinkedHashMap<String, JellyfinItem>()

    /**
     * The queue's mediaIds in playback order, as Aura built it.
     *
     * A reorder changes the player's order but NOT the order of the list the
     * user tapped (a playlist/album still holds its server order), so comparing
     * the tapped list against the live player order turned a reordered queue
     * into "restart the track". Null = unknown (never populated this session).
     */
    var queueSnapshot: List<String>? = null
        private set

    /** Upcoming order before the smart-shuffle reorder, so it can be undone. */
    var normalOrderIds: List<String>? = null

    fun put(item: JellyfinItem) {
        synchronized(lock) { items[item.id] = item }
    }

    fun putAll(list: List<JellyfinItem>) {
        synchronized(lock) { list.forEach { items[it.id] = it } }
    }

    fun get(id: String): JellyfinItem? = synchronized(lock) { items[id] }

    /** Drops every entry; returns how many were held (for the AURA_AUTH log). */
    fun clear(): Int = synchronized(lock) {
        val size = items.size
        items.clear()
        size
    }

    fun resetOrderState() {
        queueSnapshot = null
        normalOrderIds = null
    }

    // ---- Snapshot mutations (mirror every player queue mutation) ------------

    fun setQueue(ids: List<String>) {
        queueSnapshot = ids
    }

    /**
     * Inserts [ids] at [at] in the snapshot. A remote Play Next can arrive
     * between our captures; clamp so the snapshot never indexes past its own end.
     */
    fun insertIntoQueue(at: Int, ids: List<String>) {
        queueSnapshot = queueSnapshot?.toMutableList()?.also { snap ->
            snap.addAll(at.coerceIn(0, snap.size), ids)
        }
    }

    fun appendToQueue(ids: List<String>) {
        queueSnapshot = queueSnapshot?.toMutableList()?.also { snap -> snap.addAll(ids) }
    }

    fun removeFromQueue(index: Int) {
        queueSnapshot = queueSnapshot?.toMutableList()?.also { snap ->
            if (index in snap.indices) snap.removeAt(index)
        }
    }

    fun moveInQueue(from: Int, to: Int) {
        queueSnapshot = queueSnapshot?.toMutableList()?.also { snap ->
            if (from in snap.indices) snap.add(to, snap.removeAt(from))
        }
    }

    fun keepUpTo(size: Int) {
        queueSnapshot = queueSnapshot?.take(size)
    }

    /**
     * True when the tapped list is the queue that is playing.
     *
     * Content is compared as a multiset, not in order: after a Move the queue's
     * order legitimately differs from the tapped list's order, and an
     * order-sensitive check would restart the current track instead of opening
     * Now Playing. Duplicate ids (a playlist can hold the same track twice) are
     * preserved by sorting the two lists rather than deduping into a Set.
     */
    fun sameQueue(player: androidx.media3.common.Player, list: List<JellyfinItem>): Boolean {
        val live = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        if (live.sorted() != list.map { it.id }.sorted()) return false
        val snapshot = queueSnapshot
        return snapshot == null || snapshot == live
    }

    companion object {
        /**
         * Smart shuffle: least-played and longest-unplayed first, with ~20% of
         * the slots reserved for well-loved tracks so the mix still feels
         * familiar. Pure and deterministic (no RNG) — same input, same order —
         * so it is unit-testable and its result can be undone by restoring the
         * list the caller kept.
         */
        fun smartOrder(items: List<JellyfinItem>): List<JellyfinItem> {
            if (items.size < 3) return items
            val ranked = items.sortedWith(
                compareBy<JellyfinItem> { it.userData?.playCount ?: 0 }
                    .thenBy { lastPlayedEpochMs(it.userData?.lastPlayedDate) }
                    .thenBy { it.id }
            )
            val lovedCount = (ranked.size * 0.2f).roundToInt()
            if (lovedCount <= 0) return ranked
            val loved = ranked.takeLast(lovedCount) // most-played
            val fresh = ranked.take(ranked.size - lovedCount)
            if (fresh.isEmpty()) return ranked
            // Deterministic interleave: one loved track after every `step`
            // fresh ones, so favourites arrive steadily instead of in a block.
            val step = maxOf(1, fresh.size / loved.size)
            val out = ArrayList<JellyfinItem>(ranked.size)
            var li = 0
            fresh.forEachIndexed { i, item ->
                out.add(item)
                if ((i + 1) % step == 0 && li < loved.size) out.add(loved[li++])
            }
            while (li < loved.size) out.add(loved[li++])
            return out
        }

        /** ISO-8601 UTC stamp -> epoch millis; 0 (oldest) when absent/unparsable. */
        private fun lastPlayedEpochMs(raw: String?): Long {
            if (raw.isNullOrBlank()) return 0L
            val m = Regex("(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})").find(raw)
                ?: return 0L
            val g = m.groupValues
            return runCatching {
                val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
                cal.clear()
                cal.set(
                    g[1].toInt(), g[2].toInt() - 1, g[3].toInt(),
                    g[4].toInt(), g[5].toInt(), g[6].toInt()
                )
                cal.timeInMillis
            }.getOrDefault(0L)
        }
    }
}

/**
 * media3 MediaItem for a library item. The stream URI comes from the active
 * [MusicSource] — never assembled here — and never embeds the token: the
 * playback service's DataSource factory adds the Authorization header to every
 * request, so a server switch or re-sign-in is picked up automatically.
 *
 * [source] is defaulted because media items are also built from the top-level
 * queue helpers, which have no container scope; the fallback covers the window
 * before [MusicSources] is installed (JVM tests).
 */
fun toMediaItem(
    item: JellyfinItem,
    serverUrl: String,
    source: MusicSource? = MusicSources.current
): MediaItem {
    val container = item.mediaSources?.firstOrNull()?.container
    val uri = source?.streamUri(item.id, container, serverUrl)
        ?: AuthStore.audioStreamUrl(serverUrl, item.id, container)
    return MediaItem.Builder()
        .setMediaId(item.id)
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(item.name)
                .setArtist(item.artistNames.ifBlank { "Unknown artist" })
                .setAlbumTitle(item.album)
                .build()
        )
        .build()
}
