package com.hans.aura.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sort pill maps a [SortMode] onto a Jellyfin SortBy string plus a default
 * direction; both are part of the cache key, so a wrong mapping silently serves
 * the wrong ordering.
 */
class SortModeTest {

    @Test
    fun `sortBy values match the Jellyfin SortBy vocabulary`() {
        assertEquals("SortName", SortMode.TITLE.sortBy)
        assertEquals("Album", SortMode.ALBUM.sortBy)
        assertEquals("AlbumArtist", SortMode.ALBUM_ARTIST.sortBy)
        assertEquals("DateCreated", SortMode.DATE_ADDED.sortBy)
        assertEquals("Runtime", SortMode.LENGTH.sortBy)
        assertEquals("PlayCount", SortMode.PLAY_COUNT.sortBy)
        assertEquals("Random", SortMode.RANDOM.sortBy)
    }

    @Test
    fun `default direction is descending only for the newest-biggest-first sorts`() {
        assertFalse(SortMode.TITLE.defaultDesc)
        assertFalse(SortMode.ALBUM.defaultDesc)
        assertFalse(SortMode.ALBUM_ARTIST.defaultDesc)
        assertTrue(SortMode.DATE_ADDED.defaultDesc)
        assertTrue(SortMode.LENGTH.defaultDesc)
        assertTrue(SortMode.PLAY_COUNT.defaultDesc)
        assertTrue("Random has no direction", !SortMode.RANDOM.defaultDesc)
    }

    @Test
    fun `every mode has a label and the labels are unique`() {
        val labels = SortMode.entries.map { it.label }
        assertEquals(SortMode.entries.size, labels.toSet().size)
        assertTrue(labels.all { it.isNotBlank() })
    }

    @Test
    fun `every mode maps to a non-blank SortBy`() {
        assertTrue(SortMode.entries.all { it.sortBy.isNotBlank() })
    }

    @Test
    fun `Random is the only mode LibraryFilter cannot reproduce client-side`() {
        // LibraryFilter's comparator returns null for Random (server order wins);
        // every other SortBy must contain one of the fields it compares on.
        val handled = listOf("DatePlayed", "DateCreated", "Runtime", "PlayCount", "AlbumArtist", "Album", "SortName")
        SortMode.entries.filter { it != SortMode.RANDOM }.forEach { mode ->
            assertTrue(
                "${mode.name} -> ${mode.sortBy} has no client-side comparator",
                handled.any { mode.sortBy.contains(it, ignoreCase = true) }
            )
        }
        assertTrue(SortMode.RANDOM.sortBy.contains("Random", ignoreCase = true))
    }
}
