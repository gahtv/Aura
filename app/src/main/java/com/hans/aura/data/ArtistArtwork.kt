package com.hans.aura.data

internal data class ArtistArtwork(val logoUrl: String?, val bannerUrl: String?)

internal fun JellyfinItem.artistArtwork(serverUrl: String): ArtistArtwork {
    fun image(type: String, maxWidth: Int): String? =
        imageTags?.get(type)?.takeIf { it.isNotBlank() }?.let { tag ->
            AuthStore.jellyfinImageUrl(serverUrl, id, type, tag, maxWidth)
        }

    return ArtistArtwork(logoUrl = image("Logo", 640), bannerUrl = image("Banner", 1920))
}
