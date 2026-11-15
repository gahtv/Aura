package com.hans.aura.data

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Prefs (lyrics offset, recent searches) and the locally stored resume points. */
@RunWith(RobolectricTestRunner::class)
class PrefsTest {

    @Before
    fun setUp() {
        Prefs.init(ApplicationProvider.getApplicationContext())
        // ResumePoints keeps an in-memory map across contexts; start each test
        // from the (empty) prefs file of this test's application.
        val cache = ResumePoints::class.java.getDeclaredField("cache")
        cache.isAccessible = true
        cache.set(ResumePoints, null)
    }

    // ---- resume points -----------------------------------------------------

    @Test
    fun `resume point round trips`() {
        ResumePoints.save("track-1", 42_000L)
        assertEquals(42_000L, ResumePoints.position("track-1"))
    }

    @Test
    fun `resume point is dropped after a forget`() {
        ResumePoints.save("track-1", 42_000L)
        ResumePoints.forget("track-1")
        assertNull(ResumePoints.position("track-1"))
    }

    @Test
    fun `positions under five seconds are not stored`() {
        ResumePoints.save("intro", 4_999L)
        assertNull("a track barely started must not resume", ResumePoints.position("intro"))
    }

    @Test
    fun `blank ids are ignored`() {
        ResumePoints.save("", 30_000L)
        assertNull(ResumePoints.position(""))
    }

    @Test
    fun `resume points survive a fresh load from prefs`() {
        ResumePoints.save("track-1", 61_000L)
        // Drop the in-memory map so the value has to come back off disk.
        val cache = ResumePoints::class.java.getDeclaredField("cache")
        cache.isAccessible = true
        cache.set(ResumePoints, null)

        assertEquals(61_000L, ResumePoints.position("track-1"))
    }

    @Test
    fun `saving again overwrites the previous position`() {
        ResumePoints.save("track-1", 20_000L)
        ResumePoints.save("track-1", 50_000L)
        assertEquals(50_000L, ResumePoints.position("track-1"))
    }

    // ---- lyrics offset -----------------------------------------------------

    @Test
    fun `lyrics offset round trips`() {
        Prefs.setLyricsOffsetMs("track-1", 1_500L)
        assertEquals(1_500L, Prefs.lyricsOffsetMs("track-1"))
    }

    @Test
    fun `lyrics offset is clamped to plus or minus ten seconds`() {
        Prefs.setLyricsOffsetMs("high", 25_000L)
        assertEquals(10_000L, Prefs.lyricsOffsetMs("high"))

        Prefs.setLyricsOffsetMs("low", -25_000L)
        assertEquals(-10_000L, Prefs.lyricsOffsetMs("low"))
    }

    @Test
    fun `a zero offset removes the stored key`() {
        Prefs.setLyricsOffsetMs("track-1", 2_000L)
        Prefs.setLyricsOffsetMs("track-1", 0L)

        assertEquals(0L, Prefs.lyricsOffsetMs("track-1"))
        val file = Prefs.file()!!
        assertTrue(
            "zero offsets must not accumulate keys",
            file.all.none { (k, _) -> k == "lyrics_offset_track-1" }
        )
    }

    @Test
    fun `blank track ids never read or write an offset`() {
        Prefs.setLyricsOffsetMs("", 5_000L)
        assertEquals(0L, Prefs.lyricsOffsetMs(""))
        assertNotNull(Prefs.file())
    }

    // ---- recent searches ---------------------------------------------------

    @Test
    fun `recent searches are newest first`() {
        Prefs.addRecentSearch("daft punk")
        Prefs.addRecentSearch("boygenius")

        assertEquals(listOf("boygenius", "daft punk"), Prefs.recentSearches())
    }

    @Test
    fun `recent searches dedupe case-insensitively and move the term to the front`() {
        Prefs.addRecentSearch("Boygenius")
        Prefs.addRecentSearch("daft punk")
        Prefs.addRecentSearch("boygenius")

        assertEquals(listOf("boygenius", "daft punk"), Prefs.recentSearches())
    }

    @Test
    fun `recent searches are capped at eight entries`() {
        (0 until 12).forEach { Prefs.addRecentSearch("term$it") }

        val recent = Prefs.recentSearches()
        assertEquals(8, recent.size)
        assertEquals("term11", recent.first())
        assertEquals("term4", recent.last())
    }

    @Test
    fun `terms containing commas survive the round trip`() {
        Prefs.addRecentSearch("AC/DC, live")

        assertEquals(listOf("AC/DC, live"), Prefs.recentSearches())
    }

    @Test
    fun `terms are trimmed and one-character terms are rejected`() {
        Prefs.addRecentSearch("  Daft Punk  ")
        Prefs.addRecentSearch("a")

        assertEquals(listOf("Daft Punk"), Prefs.recentSearches())
    }

    @Test
    fun `recent search can be removed case-insensitively`() {
        Prefs.addRecentSearch("Daft Punk")
        Prefs.addRecentSearch("boygenius")

        Prefs.removeRecentSearch("daft punk")

        assertEquals(listOf("boygenius"), Prefs.recentSearches())
    }
}
