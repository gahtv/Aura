package com.hans.aura.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes Aura's own writes to one playlist.
 *
 * A duplicate removal is a read-modify-write: it fetches the playlist, then
 * PUTs the full id list minus one copy. If an add or a move commits between
 * that read and write, the rewrite silently erases it. The same window exists
 * between any two Aura playlist writes, so every mutating call runs its whole
 * request sequence under the playlist's lock:
 *
 * ```
 * playlistWrites.withPlaylist(id) { read(); write(); evictCache() }
 * ```
 *
 * This is only a local gate. Jellyfin exposes no conditional/atomic playlist
 * update (no ETag, no compare-and-swap), so an external client writing the same
 * playlist concurrently can still be overwritten. The server itself offers no
 * way to close that window; this class only removes the intra-app race.
 *
 * Deadlock rule: a block passed here must not call another repository method
 * that takes the same playlist's lock. All current callers keep the locked
 * region self-contained (one read followed by one write).
 *
 * One [Mutex] per playlist id. Entries are reference-counted and removed when
 * the last caller (holder or waiter) leaves, so the map stays bounded by the
 * playlists with writes in flight instead of growing with every playlist the
 * user ever edits.
 */
class PlaylistWrites {

    private class Entry(val mutex: Mutex) {
        /** Callers that hold or are waiting for [mutex]; guarded by [lock]. */
        var users: Int = 0
    }

    private val lock = Any()
    private val entries = mutableMapOf<String, Entry>()

    /**
     * Runs [block] while holding the lock for [playlistId]. Calls for the same
     * id run one at a time in arrival order; calls for different ids do not
     * wait for each other. A throwing (or cancelled) block still releases the
     * lock.
     */
    suspend fun <T> withPlaylist(playlistId: String, block: suspend () -> T): T {
        val entry = synchronized(lock) {
            entries.getOrPut(playlistId) { Entry(Mutex()) }.also { it.users++ }
        }
        try {
            return entry.mutex.withLock { block() }
        } finally {
            synchronized(lock) {
                // Safe to remove only when no waiter is queued: mutex.isLocked
                // alone is not enough, because a waiter between unlock and
                // resumption would make it look free while it still needs the
                // same instance.
                if (--entry.users == 0) entries.remove(playlistId, entry)
            }
        }
    }
}
