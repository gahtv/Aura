package com.hans.aura.ui.common

import com.hans.aura.data.JellyfinItem
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtworkResolutionTest {
    private val server = "http://jellyfin"

    @Test
    fun `now playing uses the album poster key even when the track has its own cover`() {
        val track = JellyfinItem(id = "track", albumId = "album",
            imageTags = mapOf("Primary" to "track-tag"), albumPrimaryImageTag = "album-tag")
        val album = JellyfinItem(id = "album", imageTags = mapOf("Primary" to "album-tag"))
        assertEquals(album.resolveArtwork(), track.resolveAlbumArtwork())
        assertEquals(album.resolveArtwork()?.url(server, 480), track.resolveAlbumArtwork()?.url(server, 480))
    }

    @Test
    fun `now playing artwork stays distinct for different albums`() {
        val playing = JellyfinItem(id = "track", albumId = "playing", albumPrimaryImageTag = "tag")
        val shown = JellyfinItem(id = "other", imageTags = mapOf("Primary" to "tag"))
        org.junit.Assert.assertNotEquals(shown.resolveArtwork()?.itemId, playing.resolveAlbumArtwork()?.itemId)
    }

    @Test
    fun `now playing without album falls back to track art or no art`() {
        val track = JellyfinItem(id = "track", imageTags = mapOf("Primary" to "tag"))
        assertEquals(track.resolveArtwork(), track.resolveAlbumArtwork())
        assertNull(JellyfinItem(id = "empty").resolveAlbumArtwork())
    }

    @Test
    fun `unknown album cover preserves known track artwork without matching album identity`() {
        val track = JellyfinItem(id = "track", albumId = "album", imageTags = mapOf("Primary" to "tag"))
        assertEquals(track.resolveArtwork(), track.resolveAlbumArtwork())
        assertEquals("track", track.resolveAlbumArtwork()?.itemId)
    }

    @Test
    fun `own primary image takes priority over album art`() {
        val track = JellyfinItem(
            id = "track",
            albumId = "album",
            imageTags = mapOf("Primary" to "track-tag"),
            albumPrimaryImageTag = "album-tag"
        )

        assertEquals("track", track.resolveArtwork()?.itemId)
        assertEquals(
            "$server/Items/track/Images/Primary?maxWidth=800&tag=track-tag",
            track.resolveArtwork()?.url(server, maxWidth = 800)
        )
    }

    @Test
    fun `track without own primary image uses the album and its tag`() {
        val track = Json.decodeFromString<JellyfinItem>(
            """{"Id":"track","AlbumId":"album","ImageTags":{},"AlbumPrimaryImageTag":"album-tag"}"""
        )

        assertEquals("album", track.resolveArtwork()?.itemId)
        assertEquals(
            "$server/Items/album/Images/Primary?maxWidth=480&tag=album-tag",
            track.resolveArtwork()?.url(server, maxWidth = 480)
        )
    }

    @Test
    fun `album id works even without an album image tag`() {
        val track = JellyfinItem(id = "track", albumId = "album")

        assertEquals(
            "$server/Items/album/Images/Primary?maxWidth=480",
            track.resolveArtwork()?.url(server, maxWidth = 480)
        )
    }

    @Test
    fun `item with neither own art nor an album has no artwork`() {
        assertNull(JellyfinItem(id = "track", imageTags = mapOf("Thumb" to "thumb")).resolveArtwork())
    }
}
