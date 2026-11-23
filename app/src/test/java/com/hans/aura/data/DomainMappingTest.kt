package com.hans.aura.data

import com.hans.aura.model.Album
import com.hans.aura.model.Artist
import com.hans.aura.model.ArtistRef
import com.hans.aura.model.Artwork
import com.hans.aura.model.ArtworkKind
import com.hans.aura.model.Genre
import com.hans.aura.model.Playlist
import com.hans.aura.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainMappingTest {

    @Test
    fun `track mapping derives playback and presentation state`() {
        val source = Fixtures.item(
            id = "track-1",
            name = "Track One",
            type = "Audio",
            sortName = "track sort"
        ).copy(
            album = "Album One",
            albumId = "album-1",
            albumArtist = "Album Artist",
            artists = listOf("Featured One", "Featured Two"),
            artistItems = listOf(
                NameId(id = "artist-1", name = "Featured One"),
                NameId(id = "artist-2", name = "Featured Two")
            ),
            albumArtists = listOf(NameId(id = "album-artist-1", name = "Album Artist")),
            trackNumber = 7,
            discNumber = 2,
            runTimeTicks = 2_012_500_000L,
            imageTags = mapOf("Primary" to "track-tag"),
            albumPrimaryImageTag = "album-tag",
            userData = UserData(
                playbackPositionTicks = 245_000_000L,
                playCount = 7,
                lastPlayedDate = "1970-01-02T00:00:01.0000000Z",
                isFavorite = true
            ),
            genres = listOf("Rock", "Instrumental"),
            playlistItemId = "playlist-entry-1"
        )

        val track = source.toDomainItem() as Track

        assertEquals("track-1", track.id)
        assertEquals("Track One", track.name)
        assertEquals("track sort", track.sortName)
        assertEquals("Album One", track.album)
        assertEquals("album-1", track.albumId)
        assertEquals("Album Artist", track.albumArtist)
        assertEquals("Featured One, Featured Two", track.artistNames)
        assertEquals(
            listOf(
                ArtistRef(id = "artist-1", name = "Featured One"),
                ArtistRef(id = "artist-2", name = "Featured Two")
            ),
            track.artists
        )
        assertEquals(listOf(ArtistRef(id = "album-artist-1", name = "Album Artist")), track.albumArtists)
        assertEquals(201_250L, track.durationMs)
        assertEquals(7, track.trackNumber)
        assertEquals(2, track.discNumber)
        assertEquals(listOf("Rock", "Instrumental"), track.genres)
        assertEquals("playlist-entry-1", track.playlistItemId)
        assertEquals("m4a", track.container)
        assertTrue(track.isAtmos)
        assertTrue(track.isFavorite)
        assertEquals(24_500L, track.resumePositionMs)
        assertEquals(7, track.playCount)
        assertEquals(86_401_000L, track.lastPlayedAtMs)
        assertEquals(Artwork(itemId = "track-1", tag = "track-tag"), track.artwork)
        assertEquals(Artwork(itemId = "album-1", tag = "album-tag"), track.albumArtwork)
    }

    @Test
    fun `track artist display falls back to album artists`() {
        val source = Fixtures.item(
            id = "track-2",
            codec = "flac",
            profile = null,
            container = "flac"
        ).copy(
            artists = null,
            artistItems = null,
            albumArtists = listOf(NameId(id = "artist-1", name = "Album Artist")),
            albumId = "album-1",
            albumPrimaryImageTag = null,
            imageTags = null
        )

        val track = source.toDomainItem() as Track

        assertEquals("Album Artist", track.artistNames)
        assertFalse(track.isAtmos)
        assertEquals("flac", track.container)
        assertEquals(Artwork(itemId = "album-1", tag = null), track.artwork)
        assertEquals(track.artwork, track.albumArtwork)
    }

    @Test
    fun `album artwork falls back to track artwork when the album tag is missing`() {
        val source = Fixtures.item(id = "track-3").copy(
            albumId = "album-1",
            albumPrimaryImageTag = null,
            imageTags = mapOf("Primary" to "track-tag")
        )

        val track = source.toDomainItem() as Track

        assertEquals(Artwork(itemId = "track-3", tag = "track-tag"), track.artwork)
        assertEquals(track.artwork, track.albumArtwork)
    }

    @Test
    fun `album mapping carries collection metadata`() {
        val source = Fixtures.album("album-1").copy(
            name = "Album One",
            sortName = "album sort",
            imageTags = mapOf("Primary" to "album-tag"),
            albumArtist = "Album Artist",
            artists = null,
            albumArtists = listOf(NameId(id = "artist-1", name = "Album Artist")),
            year = 2004,
            childCount = 12,
            overview = "Album overview",
            userData = UserData(isFavorite = true)
        )

        val album = source.toDomainItem() as Album

        assertEquals("album-1", album.id)
        assertEquals("Album One", album.name)
        assertEquals("album sort", album.sortName)
        assertEquals(Artwork(itemId = "album-1", tag = "album-tag"), album.artwork)
        assertEquals("Album Artist", album.albumArtist)
        assertEquals("Album Artist", album.artistNames)
        assertEquals(listOf(ArtistRef(id = "artist-1", name = "Album Artist")), album.artists)
        assertEquals(2004, album.year)
        assertEquals(12, album.childCount)
        assertEquals("Album overview", album.overview)
        assertTrue(album.isFavorite)
    }

    @Test
    fun `artist mapping carries primary logo and banner artwork`() {
        val source = Fixtures.artist("artist-1").copy(
            name = "Artist One",
            sortName = "artist sort",
            overview = "Artist biography",
            imageTags = mapOf(
                "Primary" to "primary-tag",
                "Logo" to "logo-tag",
                "Banner" to "banner-tag"
            )
        )

        val artist = source.toDomainItem() as Artist

        assertEquals("artist-1", artist.id)
        assertEquals("Artist One", artist.name)
        assertEquals("artist sort", artist.sortName)
        assertEquals("Artist biography", artist.overview)
        assertEquals(
            Artwork(itemId = "artist-1", tag = "primary-tag", kind = ArtworkKind.PRIMARY),
            artist.artwork
        )
        assertEquals(
            Artwork(itemId = "artist-1", tag = "logo-tag", kind = ArtworkKind.LOGO),
            artist.logo
        )
        assertEquals(
            Artwork(itemId = "artist-1", tag = "banner-tag", kind = ArtworkKind.BANNER),
            artist.banner
        )
    }

    @Test
    fun `playlist and genre map and unsupported types are dropped`() {
        val playlistSource = Fixtures.item(
            id = "playlist-1",
            type = "Playlist",
            codec = "flac",
            profile = null
        ).copy(
            name = "Playlist One",
            childCount = 5,
            overview = "Playlist overview",
            imageTags = mapOf("Primary" to "playlist-tag"),
            userData = UserData(isFavorite = true)
        )
        val genreSource = JellyfinItem(
            id = "genre:Rock",
            name = "Rock",
            type = "MusicGenre",
            albumId = "album-9",
            albumPrimaryImageTag = "genre-tag"
        )
        val folder = JellyfinItem(id = "folder-1", name = "Folder", type = "Folder")

        val playlist = playlistSource.toDomainItem() as Playlist
        val genre = genreSource.toDomainItem() as Genre

        assertEquals("Playlist One", playlist.name)
        assertEquals(5, playlist.childCount)
        assertEquals("Playlist overview", playlist.overview)
        assertTrue(playlist.isFavorite)
        assertEquals(Artwork(itemId = "playlist-1", tag = "playlist-tag"), playlist.artwork)
        assertEquals("Rock", genre.name)
        assertEquals(Artwork(itemId = "album-9", tag = "genre-tag"), genre.artwork)
        assertNull(folder.toDomainItem())
        assertEquals(
            listOf("playlist-1", "genre:Rock"),
            listOf(playlistSource, genreSource, folder).toDomainItems().map { it.id }
        )
    }

    @Test
    fun `missing wire state maps to neutral track defaults`() {
        val track = JellyfinItem(id = "bare", name = "Bare", type = "Audio").toDomainItem() as Track

        assertEquals("", track.artistNames)
        assertTrue(track.artists.isEmpty())
        assertTrue(track.albumArtists.isEmpty())
        assertTrue(track.genres.isEmpty())
        assertNull(track.artwork)
        assertNull(track.albumArtwork)
        assertNull(track.album)
        assertNull(track.albumId)
        assertNull(track.container)
        assertNull(track.playlistItemId)
        assertNull(track.resumePositionMs)
        assertNull(track.lastPlayedAtMs)
        assertEquals(0L, track.durationMs)
        assertEquals(0, track.playCount)
        assertFalse(track.isAtmos)
        assertFalse(track.isFavorite)
    }

    @Test
    fun `malformed last played date maps to null`() {
        val source = JellyfinItem(
            id = "track-bad-date",
            name = "Bad Date",
            type = "Audio",
            userData = UserData(lastPlayedDate = "not-a-date")
        )

        val track = source.toDomainItem() as Track

        assertNull(track.lastPlayedAtMs)
    }
}
