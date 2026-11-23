package com.hans.aura.ui.collection

import com.hans.aura.data.Fixtures
import com.hans.aura.model.Album
import com.hans.aura.model.Playlist
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The album header is the only place the client states a track count, and it
 * contradicted the catalog on seeded albums: Speak Now lists 23 tracks while 22
 * files were indexed (the 23rd is a store-only interlude).
 */
class AlbumSubtitleTest {

    private fun album(count: Int?) = Album(
        id = "1690839749",
        name = "Speak Now (Taylor's Version)",
        albumArtist = "Taylor Swift",
        childCount = count
    )

    private fun tracks(n: Int) = List(n) { Fixtures.domainTrack("t$it") }

    @Test
    fun `count matches what is served when the catalog agrees`() {
        assertEquals("22 songs \u00B7 Taylor Swift", subtitle(album(22), tracks(22)))
        assertEquals("1 song \u00B7 Taylor Swift", subtitle(album(1), tracks(1)))
    }

    @Test
    fun `a catalog with more tracks than files reads as a range`() {
        assertEquals("22 of 23 songs \u00B7 Taylor Swift", subtitle(album(23), tracks(22)))
    }

    @Test
    fun `extra indexed files do not turn into a range`() {
        // Brat: 35 rows against a 34-track listing. Nothing is missing.
        assertEquals("35 songs \u00B7 Taylor Swift", subtitle(album(34), tracks(35)))
    }

    @Test
    fun `a missing catalog count falls back to the loaded tracks`() {
        assertEquals("22 songs \u00B7 Taylor Swift", subtitle(album(null), tracks(22)))
    }

    @Test
    fun `while tracks load the catalog count is shown`() {
        assertEquals("23 songs \u00B7 Taylor Swift", subtitle(album(23), null))
    }

    @Test
    fun `an empty playlist ignores its old catalog count`() {
        val playlist = Playlist(id = "p1", name = "p1", childCount = 1)
        assertEquals("0 tracks", subtitle(playlist, tracks(0)))
    }

    @Test
    fun `a playlist with a removed entry shows only the remaining count`() {
        val playlist = Playlist(id = "p1", name = "p1", childCount = 2)
        assertEquals("1 track", subtitle(playlist, tracks(1)))
    }

    @Test
    fun `playlists say tracks, not songs`() {
        val playlist = Playlist(id = "p1", name = "Late night", childCount = 3)
        assertEquals("3 tracks", subtitle(playlist, tracks(3)))
    }
}
