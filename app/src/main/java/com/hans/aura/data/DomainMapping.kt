package com.hans.aura.data

import com.hans.aura.model.Album
import com.hans.aura.model.Artist
import com.hans.aura.model.ArtistRef
import com.hans.aura.model.Artwork
import com.hans.aura.model.ArtworkKind
import com.hans.aura.model.Genre
import com.hans.aura.model.MediaItem
import com.hans.aura.model.MediaPage
import com.hans.aura.model.Playlist
import com.hans.aura.model.Track
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Converts one wire item to the UI-facing domain model. Types Aura cannot show
 * in a music library (folders, videos, unknown future types) have no model and
 * are dropped by [toDomainItems].
 */
fun JellyfinItem.toDomainItem(): MediaItem? = when (type) {
    "Audio" -> toTrack()
    "MusicAlbum" -> toAlbum()
    "MusicArtist" -> toArtist()
    "Playlist" -> toPlaylist()
    "MusicGenre" -> toGenre()
    else -> null
}

fun List<JellyfinItem>.toDomainItems(): List<MediaItem> = mapNotNull { it.toDomainItem() }

fun List<JellyfinItem>.toTracks(): List<Track> = mapNotNull { it.toDomainItem() as? Track }

fun List<JellyfinItem>.toAlbums(): List<Album> = mapNotNull { it.toDomainItem() as? Album }

fun List<JellyfinItem>.toArtists(): List<Artist> = mapNotNull { it.toDomainItem() as? Artist }

fun List<JellyfinItem>.toPlaylists(): List<Playlist> = mapNotNull { it.toDomainItem() as? Playlist }

fun List<JellyfinItem>.toGenres(): List<Genre> = mapNotNull { it.toDomainItem() as? Genre }

internal fun ItemsResponse.toDomainPage(): MediaPage<MediaItem> =
    MediaPage(items = items.toDomainItems(), total = totalRecordCount)

internal inline fun <reified T : MediaItem> MediaPage<MediaItem>.typed(): MediaPage<T> =
    MediaPage(items = items.filterIsInstance<T>(), total = total)

private fun JellyfinItem.toTrack(): Track {
    val artwork = baseArtwork()
    return Track(
        id = id,
        name = name,
        artwork = artwork,
        albumArtwork = explicitAlbumArtwork() ?: artwork,
        album = album,
        albumId = albumId,
        albumArtist = albumArtist,
        artistNames = artistNames,
        artists = artistItems.toArtistRefs(),
        albumArtists = albumArtists.toArtistRefs(),
        durationMs = durationMs,
        trackNumber = trackNumber,
        discNumber = discNumber,
        playlistItemId = playlistItemId,
        container = mediaSources?.firstOrNull()?.container,
        isAtmos = hasAtmosStream(),
        isFavorite = userData?.isFavorite == true,
        resumePositionMs = userData?.playbackPositionTicks?.let { it / 10_000L },
        playCount = userData?.playCount ?: 0,
        lastPlayedAtMs = parseLastPlayedAtMs(userData?.lastPlayedDate)
    )
}

private fun JellyfinItem.toAlbum(): Album = Album(
    id = id,
    name = name,
    artwork = baseArtwork(),
    albumArtist = albumArtist,
    artistNames = artistNames,
    artists = albumArtists.toArtistRefs(),
    year = year,
    childCount = childCount,
    overview = overview,
    isFavorite = userData?.isFavorite == true
)

private fun JellyfinItem.toArtist(): Artist = Artist(
    id = id,
    name = name,
    artwork = baseArtwork(),
    overview = overview,
    logo = imageArtwork(ArtworkKind.LOGO),
    banner = imageArtwork(ArtworkKind.BANNER)
)

private fun JellyfinItem.toPlaylist(): Playlist = Playlist(
    id = id,
    name = name,
    artwork = baseArtwork(),
    childCount = childCount,
    overview = overview,
    isFavorite = userData?.isFavorite == true
)

private fun JellyfinItem.toGenre(): Genre = Genre(
    id = id,
    name = name,
    artwork = baseArtwork()
)

/** Own primary image first, then the album cover fallback the browse cards use today. */
private fun JellyfinItem.baseArtwork(): Artwork? =
    primaryImageTag?.let { Artwork(itemId = id, tag = it) }
        ?: albumId?.let { Artwork(itemId = it, tag = albumPrimaryImageTag) }

/** Explicit album cover only when the album tag is present; callers fall back to [baseArtwork]. */
private fun JellyfinItem.explicitAlbumArtwork(): Artwork? =
    albumId?.let { album -> albumPrimaryImageTag?.let { Artwork(itemId = album, tag = it) } }

private fun JellyfinItem.imageArtwork(kind: ArtworkKind): Artwork? {
    val wireType = when (kind) {
        ArtworkKind.PRIMARY -> "Primary"
        ArtworkKind.LOGO -> "Logo"
        ArtworkKind.BANNER -> "Banner"
    }
    val tag = imageTags?.get(wireType)?.takeIf { it.isNotBlank() } ?: return null
    return Artwork(itemId = id, tag = tag, kind = kind)
}

private fun List<NameId>?.toArtistRefs(): List<ArtistRef> =
    orEmpty().map { ArtistRef(id = it.id, name = it.name) }

/** Atmos = DD+ with JOC object coding; only E-AC3 JOC survives as Atmos. */
private fun JellyfinItem.hasAtmosStream(): Boolean =
    mediaSources?.any { source ->
        source.mediaStreams?.any { stream ->
            stream.codec.equals("eac3", ignoreCase = true) &&
                (stream.profile?.contains("JOC", ignoreCase = true) == true ||
                    stream.profile?.contains("Dolby Atmos", ignoreCase = true) == true)
        } == true
    } == true

private fun parseLastPlayedAtMs(raw: String?): Long? {
    if (raw.isNullOrBlank()) return null
    return runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
}
