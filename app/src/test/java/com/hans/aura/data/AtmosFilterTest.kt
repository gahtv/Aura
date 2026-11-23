package com.hans.aura.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * "Only show Atmos content" must hide the standard copy of a release and keep
 * everything that is not a track/album (an artist with both copies stays
 * reachable, a playlist must never lose items).
 */
class AtmosFilterTest {

    private val atmosAlbum = Fixtures.album("album-atmos")
    private val flacAlbum = Fixtures.album("album-flac")
    private val atmosTrack = Fixtures.track("track-atmos")
    private val flacTrack = Fixtures.track("track-flac")
    private val artist = Fixtures.artist("artist-1")
    private val playlist = Fixtures.item("playlist-1", type = "Playlist", codec = "flac", profile = null)

    @Before
    fun setUp() {
        AtmosFilter.setEnabled(false)
        AtmosFilter.adopt(
            AtmosCatalog.Catalog(
                albumIds = setOf("album-atmos"),
                trackIds = setOf("track-atmos")
            )
        )
    }

    @Test
    fun `everything passes through when the filter is off`() {
        assertTrue(AtmosFilter.keep(atmosAlbum.toDomainItem()!!))
        assertTrue(AtmosFilter.keep(flacAlbum.toDomainItem()!!))
        assertTrue(AtmosFilter.keep(atmosTrack.toDomainItem()!!))
        assertTrue(AtmosFilter.keep(flacTrack.toDomainItem()!!))
        assertEqualsLists(
            listOf(atmosTrack, flacTrack),
            AtmosFilter.tracks(listOf(atmosTrack, flacTrack))
        )
    }

    @Test
    fun `albums outside the Atmos catalog are dropped`() {
        AtmosFilter.setEnabled(true)
        assertTrue(AtmosFilter.keep(atmosAlbum.toDomainItem()!!))
        assertFalse(AtmosFilter.keep(flacAlbum.toDomainItem()!!))
    }

    @Test
    fun `tracks outside the Atmos track set are dropped`() {
        AtmosFilter.setEnabled(true)
        assertTrue(AtmosFilter.keep(atmosTrack.toDomainItem()!!))
        assertFalse(AtmosFilter.keep(flacTrack.toDomainItem()!!))
    }

    @Test
    fun `artists and playlists are never filtered`() {
        AtmosFilter.setEnabled(true)
        assertTrue("an artist with both copies must stay reachable", AtmosFilter.keep(artist.toDomainItem()!!))
        assertTrue("filtering a playlist would hide its contents", AtmosFilter.keep(playlist.toDomainItem()!!))
    }

    @Test
    fun `albums and tracks helpers filter the list only when on`() {
        val list = listOf(atmosAlbum, flacAlbum)
        assertEqualsLists(list, AtmosFilter.albums(list))

        AtmosFilter.setEnabled(true)
        assertEqualsLists(listOf(atmosAlbum), AtmosFilter.albums(list))
        assertEqualsLists(listOf(atmosTrack), AtmosFilter.tracks(listOf(atmosTrack, flacTrack)))
    }

    @Test
    fun `an empty catalog hides every album and track while on`() {
        AtmosFilter.adopt(AtmosCatalog.Catalog())
        AtmosFilter.setEnabled(true)
        assertFalse(AtmosFilter.keep(atmosAlbum.toDomainItem()!!))
        assertFalse(AtmosFilter.keep(atmosTrack.toDomainItem()!!))
        assertTrue(AtmosFilter.keep(artist.toDomainItem()!!))
    }

    private fun assertEqualsLists(expected: List<JellyfinItem>, actual: List<JellyfinItem>) {
        org.junit.Assert.assertEquals(expected.map { it.id }, actual.map { it.id })
    }
}
