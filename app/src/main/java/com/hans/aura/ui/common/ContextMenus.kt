package com.hans.aura.ui.common

import com.hans.aura.AuraApp
import com.hans.aura.data.JellyfinItem
import com.hans.aura.data.LibraryCache
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
                actions.add(ContextAction("Move up") { player.moveInQueue(index, index - 1) })
                actions.add(ContextAction("Move to top") { player.moveInQueue(index, 0) })
            }
            if (index < size - 1) {
                actions.add(ContextAction("Move down") { player.moveInQueue(index, index + 1) })
            }
        }
        actions.add(ContextAction("Remove from queue") { player.removeFromQueue(index) })
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
            ContextAction("Add to playlist") {
                // The picker replaces the menu: close it first or it keeps
                // stealing key focus from the picker.
                ContextMenuHost.current = null
                ContextMenuHost.openPicker(listOf(track))
            },
            ContextAction("Play next") { player.playNext(listOf(track)) },
            ContextAction("Add to queue") { player.addToQueue(listOf(track)) }
        )
        // Reorder actions lead the playlist menu: they are what the long-press
        // is reached for on a TV.
        playlistMove?.let { pm ->
            if (pm.index > 0) {
                actions.add(0, ContextAction("Move to top") { movePlaylistItem(app, scope, pm, 0) })
                actions.add(1, ContextAction("Move up") { movePlaylistItem(app, scope, pm, pm.index - 1) })
            }
            if (pm.index < pm.size - 1) {
                actions.add(ContextAction("Move down") { movePlaylistItem(app, scope, pm, pm.index + 1) })
            }
        }
        val artistId = track.artistItems?.firstOrNull()?.id
            ?: track.albumArtists?.firstOrNull()?.id
        if (onOpenArtist != null && artistId != null) {
            actions.add(
                ContextAction("View artist") {
                    scope.launch {
                        runCatching {
                            app.jellyfin.api.getItem(app.jellyfin.auth.session!!.userId, artistId)
                        }.onSuccess { onOpenArtist(it) }
                    }
                }
            )
        }
        if (onOpenCollection != null && track.albumId != null) {
            actions.add(
                ContextAction("View album") {
                    scope.launch {
                        runCatching {
                            app.jellyfin.api.getItem(
                                app.jellyfin.auth.session!!.userId, track.albumId!!
                            )
                        }.onSuccess { onOpenCollection(it) }
                    }
                }
            )
        }
        onRemoveFromPlaylist?.let {
            actions.add(ContextAction("Remove from playlist") { it() })
        }
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
            ContextAction("Play next") {
                scope.launch { albumTracks(app, album)?.let { player.playNext(it) } }
            },
            ContextAction("Add to queue") {
                scope.launch { albumTracks(app, album)?.let { player.addToQueue(it) } }
            },
            ContextAction("Add to playlist") {
                scope.launch {
                    ContextMenuHost.current = null
                    albumTracks(app, album)?.let { ContextMenuHost.openPicker(it) }
                }
            }
        )
        val artistId = album.albumArtists?.firstOrNull()?.id
        if (onOpenArtist != null && artistId != null) {
            actions.add(
                ContextAction("View artist") {
                    scope.launch {
                        runCatching {
                            app.jellyfin.api.getItem(app.jellyfin.auth.session!!.userId, artistId)
                        }.onSuccess { onOpenArtist(it) }
                    }
                }
            )
        }
        return ContextMenuSpec(
            title = album.name,
            subtitle = album.albumArtist ?: album.artistNames.ifBlank { null },
            actions = actions
        )
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
                }
            }.onFailure {
                android.util.Log.w("AURA_QUEUE", "playlist move failed: ${it.message}")
            }
        }
    }

    /** All audio tracks of an album/playlist, newest last (play order). */
    private suspend fun albumTracks(app: AuraApp, album: JellyfinItem): List<JellyfinItem>? =
        runCatching {
            app.jellyfin.api.getItems(
                userId = app.jellyfin.auth.session!!.userId,
                parentId = album.id,
                includeItemTypes = "Audio",
                sortBy = "ParentIndexNumber,IndexNumber",
                sortOrder = "Ascending",
                limit = 500
            ).items
        }.getOrNull()
}
