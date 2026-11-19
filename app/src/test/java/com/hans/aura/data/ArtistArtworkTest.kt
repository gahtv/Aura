package com.hans.aura.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ArtistArtworkTest {
    private val serverUrl = "http://example.test:8096/jellyfin"

    @Test
    fun `artist artwork uses Jellyfin image types and their matching cache tags`() {
        val artist = Json.decodeFromString<JellyfinItem>(
            """{"Id":"artist-1","Type":"MusicArtist","ImageTags":{"Primary":"cover","Logo":"logo","Banner":"banner"}}"""
        )

        assertEquals(
            ArtistArtwork(
                logoUrl = "$serverUrl/Items/artist-1/Images/Logo?maxWidth=640&tag=logo",
                bannerUrl = "$serverUrl/Items/artist-1/Images/Banner?maxWidth=1920&tag=banner"
            ),
            artist.artistArtwork(serverUrl)
        )
        assertEquals("cover", artist.primaryImageTag)
    }

    @Test
    fun `missing and blank tags leave each kind of artwork optional`() {
        val stub = JellyfinItem(id = "artist-1")
        val expectedLogo = "$serverUrl/Items/artist-1/Images/Logo?maxWidth=640&tag=logo"
        val expectedBanner = "$serverUrl/Items/artist-1/Images/Banner?maxWidth=1920&tag=banner"

        assertEquals(ArtistArtwork(null, null), stub.artistArtwork(serverUrl))
        assertEquals(
            ArtistArtwork(null, null),
            stub.copy(imageTags = mapOf("Primary" to "cover")).artistArtwork(serverUrl)
        )
        assertEquals(
            ArtistArtwork(expectedLogo, null),
            stub.copy(imageTags = mapOf("Logo" to "logo", "Banner" to "")).artistArtwork(serverUrl)
        )
        assertEquals(
            ArtistArtwork(null, expectedBanner),
            stub.copy(imageTags = mapOf("Logo" to " ", "Banner" to "banner")).artistArtwork(serverUrl)
        )
    }
}
