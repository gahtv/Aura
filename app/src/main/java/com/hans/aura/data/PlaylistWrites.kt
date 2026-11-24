package com.hans.aura.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Result of one playlist edit, as far as Aura can know it.
 *
 * [FAILED] means the server rejected the write (4xx), or a read/local
 * precondition stopped it before any write request was sent: the server list
 * is unchanged, so the visible order is still valid.
 * [UNKNOWN] means a write was attempted but its response was lost (I/O error,
 * timeout, 5xx, cancellation after send): the edit may or may not have
 * committed, so the cached order is no longer trustworthy.
 * [SUCCESS] means the server accepted the write.
 */
enum class PlaylistEditOutcome { SUCCESS, FAILED, UNKNOWN }

/**
 * A playlist edit failure that carries whether the server can have applied it.
 *
 * The data layer is the only place that knows which request is the write, so
 * it decides [outcome] there instead of making the UI infer it from an
 * exception type: a timeout during the initial GET never reached the server
 * ([FAILED]), while a timeout after the write was sent may have ([UNKNOWN]).
 * [message] is the underlying failure's message so callers can keep showing
 * it verbatim.
 */
class PlaylistEditException(
    val outcome: PlaylistEditOutcome,
    cause: Throwable
) : Exception(cause.message, cause)

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
