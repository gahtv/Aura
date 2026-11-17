package com.hans.aura.ui.common

import android.util.Log
import com.hans.aura.AuraApp
import com.hans.aura.R
import com.hans.aura.data.JellyfinItem
import com.hans.aura.data.LibraryCache
import com.hans.aura.data.Mixes
import com.hans.aura.data.SourceQueries
import com.hans.aura.ui.player.PlayerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Builds context menus for long-press on tracks and albums.
 *
 * Queue mutations go through PlayerConnection; "Add to playlist" opens the
 * shared PlaylistPickerDialog via ContextMenuHost.openPicker (AppRoot hosts
 * it). "View artist/album" resolves the target item from the server, then
 * hands it to the screen's existing navigation callback.
 */
object ContextMenus {

    private const val TAG = "AURA_MENU"

    /**
     * Queue row (QueueScreen): menu-driven reorder. A TV has no pointer, so
     * "Move up / down / to top" replaces dragging. Moves that cannot work —
     * first row up/ to top, last row down — are omitted rather than shown dead,
     * and the currently playing row is not movable (moving the playing entry
     * makes "what's next" ambiguous against the live player index) but can still
     * be removed.
     */
    fun forQueueRow(
        player: PlayerConnection,
        index: Int,
        size: Int,
        currentIndex: Int,
        track: JellyfinItem
    ): ContextMenuSpec {
        val actions = mutableListOf<ContextAction>()
        if (index != currentIndex) {
            if (index > 0) {
                actions.add(ContextAction("Move up", R.drawable.ic_arrow_upward) { player.moveInQueue(index, index - 1) })
                actions.add(ContextAction("Move to top", R.drawable.ic_vertical_align_top) { player.moveInQueue(index, 0) })
            }
            if (index < size - 1) {
                actions.add(ContextAction("Move down", R.drawable.ic_arrow_downward) { player.moveInQueue(index, index + 1) })
            }
        }
        actions.add(ContextAction("Remove from queue", R.drawable.ic_delete) { player.removeFromQueue(index) })
        return ContextMenuSpec(
            title = track.name,
            subtitle = track.artistNames.ifBlank { null },
            actions = actions
        )
    }

    /**
     * A playlist row that can be reordered server-side. The move endpoint is
     * POST /Playlists/{playlistId}/Items/{itemId}/Move/{newIndex} (204 on
     * success) and, like every Retrofit Response, never throws on 4xx/5xx — so
     * the code is checked before the cache is evicted and the list refreshed.
     */
    data class PlaylistMove(
        val playlistId: String,
        val itemId: String,
        val index: Int,
        val size: Int,
        val onMoved: () -> Unit
    )

    fun forTrack(
        app: AuraApp,
        player: PlayerConnection,
        scope: CoroutineScope,
        track: JellyfinItem,
        onOpenArtist: ((JellyfinItem) -> Unit)?,
        onOpenCollection: ((JellyfinItem) -> Unit)?,
        onRemoveFromPlaylist: (() -> Unit)? = null,
        playlistMove: PlaylistMove? = null
    ): ContextMenuSpec {
        val actions = mutableListOf(
            ContextAction("Add to playlist", R.drawable.ic_playlist_add) {
                // The picker replaces the menu: close it first or it keeps
                // stealing key focus from the picker.
                ContextMenuHost.current = null
                ContextMenuHost.openPicker(listOf(track))
            },
            ContextAction("Play next", R.drawable.ic_queue_play_next) { player.playNext(listOf(track)) },
            ContextAction("Add to queue", R.drawable.ic_queue_music) { player.addToQueue(listOf(track)) },
            // InstantMix from this track: GET /Items/{trackId}/InstantMix,
            // ~50 tracks replacing the queue (AURA_MIX breadcrumb in Mixes).
            ContextAction("Start radio", R.drawable.ic_radio) {
                val userId = app.jellyfin.auth.session?.userId
                if (userId == null) {
                    Log.w(TAG, "start radio skipped: no session")
                } else {
                    scope.launch {
                        Mixes.playMix(
                            api = app.jellyfin.api,
                            userId = userId,
                            player = player,
                            kind = Mixes.Kind.TRACK,
                            seed = track
                        )
                    }
                }
            }
        )
        // Reorder actions lead the playlist menu: they are what the long-press
        // is reached for on a TV.
        playlistMove?.let { pm ->
            if (pm.index > 0) {
                actions.add(0, ContextAction("Move to top", R.drawable.ic_vertical_align_top) { movePlaylistItem(app, scope, pm, 0) })
                actions.add(1, ContextAction("Move up", R.drawable.ic_arrow_upward) { movePlaylistItem(app, scope, pm, pm.index - 1) })
            }
            if (pm.index < pm.size - 1) {
                actions.add(ContextAction("Move down", R.drawable.ic_arrow_downward) { movePlaylistItem(app, scope, pm, pm.index + 1) })
            }
        }
        val artistId = track.artistItems?.firstOrNull()?.id
            ?: track.albumArtists?.firstOrNull()?.id
        if (onOpenArtist != null && artistId != null) {
            actions.add(
                ContextAction("View artist", R.drawable.ic_person) {
                    val userId = app.jellyfin.auth.session?.userId
                    if (userId == null) {
                        Log.w(TAG, "view artist skipped: no session")
                    } else {
                        scope.launch {
                            runCatching { app.jellyfin.api.getItem(userId, artistId) }
                                .onSuccess { onOpenArtist(it) }
                                .onFailure { Log.w(TAG, "view artist $artistId failed: ${it.message}") }
                        }
                    }
                }
            )
        }
        val albumId = track.albumId
        if (onOpenCollection != null && albumId != null) {
            actions.add(
                ContextAction("View album", R.drawable.ic_album) {
                    val userId = app.jellyfin.auth.session?.userId
                    if (userId == null) {
                        Log.w(TAG, "view album skipped: no session")
                    } else {
                        scope.launch {
                            runCatching { app.jellyfin.api.getItem(userId, albumId) }
                                .onSuccess { onOpenCollection(it) }
                                .onFailure { Log.w(TAG, "view album $albumId failed: ${it.message}") }
                        }
                    }
                }
            )
        }
        onRemoveFromPlaylist?.let {
            actions.add(ContextAction("Remove from playlist", R.drawable.ic_playlist_remove) { it() })
        }
        // Liked songs sit FIRST — above "Play next" and above the playlist
        // reorder rows (inserted after them so nothing can push it down).
        actions.add(0, favoriteAction(app, scope, track))
        return ContextMenuSpec(
            title = track.name,
            subtitle = track.artistNames.ifBlank { null },
            actions = actions
        )
    }

    fun forAlbum(
        app: AuraApp,
        player: PlayerConnection,
        scope: CoroutineScope,
        album: JellyfinItem,
        onOpenArtist: ((JellyfinItem) -> Unit)?
    ): ContextMenuSpec {
        val actions = mutableListOf(
            ContextAction("Play next", R.drawable.ic_queue_play_next) {
                scope.launch { albumTracks(album)?.let { player.playNext(it) } }
            },
            ContextAction("Add to queue", R.drawable.ic_queue_music) {
                scope.launch { albumTracks(album)?.let { player.addToQueue(it) } }
            },
            ContextAction("Add to playlist", R.drawable.ic_playlist_add) {
                scope.launch {
                    ContextMenuHost.current = null
                    val tracks = albumTracks(album)
                    if (tracks == null) {
                        Log.w(TAG, "add album ${album.id} to playlist skipped: no tracks")
                    } else {
                        ContextMenuHost.openPicker(tracks)
                    }
                }
            }
        )
        val artistId = album.albumArtists?.firstOrNull()?.id
        if (onOpenArtist != null && artistId != null) {
            actions.add(
                ContextAction("View artist", R.drawable.ic_person) {
                    val userId = app.jellyfin.auth.session?.userId
                    if (userId == null) {
                        Log.w(TAG, "view artist skipped: no session")
                    } else {
                        scope.launch {
                            runCatching { app.jellyfin.api.getItem(userId, artistId) }
                                .onSuccess { onOpenArtist(it) }
                                .onFailure { Log.w(TAG, "view artist $artistId failed: ${it.message}") }
                        }
                    }
                }
            )
        }
        return ContextMenuSpec(
            title = album.name,
            subtitle = album.albumArtist ?: album.artistNames.ifBlank { null },
            actions = listOf(favoriteAction(app, scope, album)) + actions
        )
    }

    /**
     * "Add to / Remove from favorites" — the label is decided when the menu
     * opens from the session's favourite state, and the toggle itself is
     * optimistic (Favorites reverts it if the server rejects the request).
     */
    private fun favoriteAction(
        app: AuraApp,
        scope: CoroutineScope,
        item: JellyfinItem
    ): ContextAction {
        val liked = com.hans.aura.data.Favorites.isFavorite(item)
        return ContextAction(
            if (liked) "Remove from favorites" else "Add to favorites",
            if (liked) R.drawable.ic_favorite_fill1 else R.drawable.ic_favorite
        ) {
            scope.launch {
                val userId = app.jellyfin.auth.session?.userId
                if (userId == null) {
                    Log.w(TAG, "favourite toggle skipped: no session")
                } else {
                    com.hans.aura.data.Favorites.toggle(app.jellyfin.api, userId, item)
                }
            }
        }
    }

    /**
     * Server-side playlist reorder. Retrofit's Response never throws on 4xx/5xx,
     * so the status code decides whether the move happened: on failure the row
     * order and the 24h cache stay untouched (and the log carries the code) —
     * blindly evicting + refreshing would silently show the OLD order while
     * looking like success.
     */
    private fun movePlaylistItem(
        app: AuraApp,
        scope: CoroutineScope,
        move: PlaylistMove,
        newIndex: Int
    ) {
        scope.launch {
            runCatching {
                app.jellyfin.api.movePlaylistItem(move.playlistId, move.itemId, newIndex)
            }.onSuccess { resp ->
                android.util.Log.d(
                    "AURA_QUEUE",
                    "playlist move playlist=${move.playlistId} item=${move.itemId} " +
                        "newIndex=$newIndex -> code=${resp.code()}"
                )
                if (resp.isSuccessful) {
                    // The playlist screen reads col:<playlistId> from
                    // LibraryCache with a 24h TTL — without this eviction the
                    // page keeps rendering the pre-move order for a day.
                    LibraryCache.evict("col:${move.playlistId}")
                    move.onMoved()
                } else {
                    Log.w(TAG, "playlist move rejected: HTTP ${resp.code()}")
                }
            }.onFailure {
                android.util.Log.w("AURA_QUEUE", "playlist move failed: ${it.message}")
            }
        }
    }

    /** All audio tracks of an album/playlist, newest last (play order). */
    private suspend fun albumTracks(album: JellyfinItem): List<JellyfinItem>? = runCatching {
        SourceQueries.items(
            parentId = album.id,
            includeItemTypes = "Audio",
            sortBy = "ParentIndexNumber,IndexNumber",
            sortOrder = "Ascending",
            limit = 500
        ).items
    }.onFailure { Log.w(TAG, "album tracks ${album.id} failed: ${it.message}") }
        .getOrNull()
}
