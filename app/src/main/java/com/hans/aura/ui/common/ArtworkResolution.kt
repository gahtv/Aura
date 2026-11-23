package com.hans.aura.ui.common

import com.hans.aura.data.AuthStore
import com.hans.aura.data.JellyfinItem

internal data class ArtworkRef(val itemId: String, private val tag: String?) {
    fun url(serverUrl: String, maxWidth: Int): String? =
        AuthStore.imageUrl(serverUrl, itemId, tag, maxWidth)
}

internal fun JellyfinItem.resolveArtwork(): ArtworkRef? =
    primaryImageTag?.let { ArtworkRef(id, it) }
        ?: albumId?.let { ArtworkRef(it, albumPrimaryImageTag) }

internal fun JellyfinItem.resolveAlbumArtwork(): ArtworkRef? =
    albumId?.let { album -> albumPrimaryImageTag?.let { ArtworkRef(album, it) } } ?: resolveArtwork()
