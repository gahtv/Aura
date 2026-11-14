package com.hans.aura.ui.common

import com.hans.aura.AuraApp
import com.hans.aura.data.JellyfinItem
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

    fun forTrack(
        app: AuraApp,
        player: PlayerConnection,
        scope: CoroutineScope,
        track: JellyfinItem,
        onOpenArtist: ((JellyfinItem) -> Unit)?,
        onOpenCollection: ((JellyfinItem) -> Unit)?,
        onRemoveFromPlaylist: (() -> Unit)? = null
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
