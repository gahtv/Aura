package com.hans.aura.data

import com.hans.aura.model.Artist

internal data class ArtistArtwork(val logoUrl: String?, val bannerUrl: String?)

internal fun Artist.artistArtwork(serverUrl: String): ArtistArtwork = ArtistArtwork(
    logoUrl = logo?.let { AuthStore.jellyfinImageUrl(serverUrl, it.itemId, "Logo", it.tag, 640) },
    bannerUrl = banner?.let { AuthStore.jellyfinImageUrl(serverUrl, it.itemId, "Banner", it.tag, 1920) }
)
