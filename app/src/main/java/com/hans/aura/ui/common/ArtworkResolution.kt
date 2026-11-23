package com.hans.aura.ui.common

import com.hans.aura.data.AuthStore
import com.hans.aura.model.Artwork
import com.hans.aura.model.ArtworkKind

internal fun Artwork.url(serverUrl: String, maxWidth: Int): String? = when (kind) {
    ArtworkKind.PRIMARY -> AuthStore.imageUrl(serverUrl, itemId, tag, maxWidth)
    else -> AuthStore.jellyfinImageUrl(serverUrl, itemId, kind.name.lowercase().replaceFirstChar { it.uppercase() }, tag, maxWidth)
}
