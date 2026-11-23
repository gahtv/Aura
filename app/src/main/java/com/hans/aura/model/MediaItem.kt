package com.hans.aura.model

import androidx.compose.runtime.Immutable

/** Reference to one image on the backend; resolution to a URL stays in the data layer. */
@Immutable
data class Artwork(
    val itemId: String,
    val tag: String? = null,
    val kind: ArtworkKind = ArtworkKind.PRIMARY
)

enum class ArtworkKind {
    PRIMARY,
    LOGO,
    BANNER
}

@Immutable
data class ArtistRef(
    val id: String,
    val name: String
)

/**
 * UI-facing library item. List values are never mutated after construction.
 */
@Immutable
sealed interface MediaItem {
    val id: String
    val name: String
    val sortName: String?
    val artwork: Artwork?
}

@Immutable
data class Track(
    override val id: String,
    override val name: String,
    override val sortName: String? = null,
    override val artwork: Artwork? = null,
    val albumArtwork: Artwork? = null,
    val album: String? = null,
    val albumId: String? = null,
    val albumArtist: String? = null,
    val artistNames: String = "",
    val artists: List<ArtistRef> = emptyList(),
    val albumArtists: List<ArtistRef> = emptyList(),
    val durationMs: Long = 0L,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val genres: List<String> = emptyList(),
    val playlistItemId: String? = null,
    val container: String? = null,
    val isAtmos: Boolean = false,
    val isFavorite: Boolean = false,
    val resumePositionMs: Long? = null,
    val playCount: Int = 0,
    val lastPlayedAtMs: Long? = null
) : MediaItem

@Immutable
data class Album(
    override val id: String,
    override val name: String,
    override val sortName: String? = null,
    override val artwork: Artwork? = null,
    val albumArtist: String? = null,
    val artistNames: String = "",
    val artists: List<ArtistRef> = emptyList(),
    val year: Int? = null,
    val childCount: Int? = null,
    val overview: String? = null,
    val isFavorite: Boolean = false
) : MediaItem

@Immutable
data class Artist(
    override val id: String,
    override val name: String,
    override val sortName: String? = null,
    override val artwork: Artwork? = null,
    val overview: String? = null,
    val logo: Artwork? = null,
    val banner: Artwork? = null
) : MediaItem

@Immutable
data class Playlist(
    override val id: String,
    override val name: String,
    override val sortName: String? = null,
    override val artwork: Artwork? = null,
    val childCount: Int? = null,
    val overview: String? = null,
    val isFavorite: Boolean = false
) : MediaItem

@Immutable
data class Genre(
    override val id: String,
    override val name: String,
    override val sortName: String? = null,
    override val artwork: Artwork? = null
) : MediaItem
