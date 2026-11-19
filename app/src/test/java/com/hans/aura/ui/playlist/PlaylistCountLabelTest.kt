package com.hans.aura.ui.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistCountLabelTest {
    @Test
    fun `missing count has no label`() {
        assertNull(playlistCountLabel(null))
    }

    @Test
    fun `zero count is not missing`() {
        assertEquals("0 tracks", playlistCountLabel(0))
    }

    @Test
    fun `known counts use singular and plural`() {
        assertEquals("1 track", playlistCountLabel(1))
        assertEquals("5 tracks", playlistCountLabel(5))
    }
}
