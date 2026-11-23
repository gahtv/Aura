package com.hans.aura.ui.common

import com.hans.aura.data.JellyfinItem
import com.hans.aura.data.toDomainItem
import com.hans.aura.model.Track
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtworkResolutionTest {
    private val server = "http://jellyfin"

    private fun artworkOf(item: JellyfinItem) = (item.toDomainItem() as Track).artwork

    @Test
    fun `own primary image takes priority over album art`() {
        val track = JellyfinItem(
            id = "track",
            type = "Audio",
            albumId = "album",
            imageTags = mapOf("Primary" to "track-tag"),
            albumPrimaryImageTag = "album-tag"
        )

        assertEquals("track", artworkOf(track)?.itemId)
        assertEquals(
            "$server/Items/track/Images/Primary?maxWidth=800&tag=track-tag",
            artworkOf(track)?.url(server, maxWidth = 800)
        )
    }

    @Test
    fun `track without own primary image uses the album and its tag`() {
        val track = Json.decodeFromString<JellyfinItem>(
            """{"Id":"track","Type":"Audio","AlbumId":"album","ImageTags":{},"AlbumPrimaryImageTag":"album-tag"}"""
        )

        assertEquals("album", artworkOf(track)?.itemId)
        assertEquals(
            "$server/Items/album/Images/Primary?maxWidth=480&tag=album-tag",
            artworkOf(track)?.url(server, maxWidth = 480)
        )
    }

    @Test
    fun `album id works even without an album image tag`() {
        val track = JellyfinItem(id = "track", type = "Audio", albumId = "album")

        assertEquals(
            "$server/Items/album/Images/Primary?maxWidth=480",
            artworkOf(track)?.url(server, maxWidth = 480)
        )
    }

    @Test
    fun `item with neither own art nor an album has no artwork`() {
        assertNull(artworkOf(JellyfinItem(id = "track", type = "Audio", imageTags = mapOf("Thumb" to "thumb"))))
    }
}
