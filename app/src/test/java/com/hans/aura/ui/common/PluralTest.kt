package com.hans.aura.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

/** Counts on TV screens must never read "1 songs". */
class PluralTest {

    @Test
    fun `exactly one takes the singular`() {
        assertEquals("1 song", countLabel(1, "song"))
        assertEquals("1 album", countLabel(1, "album"))
    }

    @Test
    fun `zero and many take the plural`() {
        assertEquals("0 songs", countLabel(0, "song"))
        assertEquals("2 songs", countLabel(2, "song"))
        assertEquals("23 songs", countLabel(23, "song"))
    }

    @Test
    fun `count is always printed`() {
        assertEquals("0 tracks", countLabel(0, "track"))
        assertEquals("1 track", countLabel(1, "track"))
        assertEquals("5 tracks", countLabel(5, "track"))
        assertEquals("7 tracks", countLabel(7, "track"))
    }

    @Test
    fun `an irregular plural can be supplied`() {
        assertEquals("1 artist", countLabel(1, "artist", "artists"))
        assertEquals("3 artists", countLabel(3, "artist", "artists"))
    }
}
