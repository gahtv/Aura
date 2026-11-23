package com.hans.aura.ui.player

import com.hans.aura.data.Fixtures
import com.hans.aura.data.toDomainItem
import com.hans.aura.model.Track
import com.hans.aura.data.MediaSource
import com.hans.aura.data.MediaStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Atmos detection (E-AC-3 JOC only) and smart-shuffle ordering. */
class PlayerConnectionTest {

    private fun withStream(codec: String, profile: String?): Track =
        Fixtures.item("x", codec = codec, profile = profile).toDomainItem() as Track

    // ---- isAtmos -----------------------------------------------------------

    @Test
    fun `eac3 with JOC profile is Atmos`() {
        assertTrue(PlayerConnection.isAtmos(withStream("eac3", "JOC")))
    }

    @Test
    fun `profile matching is case insensitive`() {
        assertTrue(PlayerConnection.isAtmos(withStream("EAC3", "joc")))
        assertTrue(PlayerConnection.isAtmos(withStream("eac3", "dolby atmos")))
    }

    @Test
    fun `dolby atmos profile string is Atmos`() {
        assertTrue(PlayerConnection.isAtmos(withStream("eac3", "Dolby Atmos")))
    }

    @Test
    fun `plain E-AC-3 without JOC is not Atmos`() {
        assertFalse(PlayerConnection.isAtmos(withStream("eac3", null)))
        assertFalse(PlayerConnection.isAtmos(withStream("eac3", "Dolby Digital Plus")))
    }

    @Test
    fun `plain AC-3 FLAC and AAC are not Atmos`() {
        assertFalse(PlayerConnection.isAtmos(withStream("ac3", "Dolby Digital")))
        assertFalse(PlayerConnection.isAtmos(withStream("flac", null)))
        assertFalse(PlayerConnection.isAtmos(withStream("aac", "LC")))
    }

    @Test
    fun `item with a JOC stream among several is Atmos`() {
        val item = Fixtures.item("multi", codec = "aac", profile = "LC").copy(
            mediaSources = listOf(
                MediaSource(
                    id = "s",
                    mediaStreams = listOf(
                        MediaStream(codec = "aac", profile = "LC"),
                        MediaStream(codec = "eac3", profile = "JOC")
                    )
                )
            )
        ).toDomainItem() as Track
        assertTrue(PlayerConnection.isAtmos(item))
    }

    @Test
    fun `null and stream-less items are not Atmos`() {
        assertFalse(PlayerConnection.isAtmos(null))
        assertFalse(PlayerConnection.isAtmos(Track(id = "empty", name = "empty")))
    }

    // ---- smartOrder --------------------------------------------------------

    @Test
    fun `fewer than three items keep their order`() {
        val two = listOf(track("a", 9), track("b", 0))
        assertEquals(listOf("a", "b"), PlayerConnection.smartOrder(two).map { it.id })
        assertEquals(listOf("a"), PlayerConnection.smartOrder(listOf(track("a", 5))).map { it.id })
        assertEquals(emptyList<String>(), PlayerConnection.smartOrder(emptyList()).map { it.id })
    }

    @Test
    fun `ordering is deterministic`() {
        val items = (0 until 10).map { track("i$it", it) }
        val first = PlayerConnection.smartOrder(items).map { it.id }
        val second = PlayerConnection.smartOrder(items).map { it.id }
        assertEquals(first, second)
    }

    @Test
    fun `least played comes first and the favourite is pushed back`() {
        val items = listOf(
            track("a", 5),
            track("b", 0),
            track("c", 10),
            track("d", 1)
        )

        val ordered = PlayerConnection.smartOrder(items).map { it.id }

        assertEquals("b", ordered.first())
        assertEquals("the most-played track is not first", "c", ordered.last())
        assertEquals(items.size, ordered.size)
        assertEquals(items.map { it.id }.toSet(), ordered.toSet())
    }

    @Test
    fun `longest unplayed comes first when play counts tie`() {
        val items = listOf(
            track("recent", 2, "2026-01-01T10:00:00Z"),
            track("old", 2, "2020-01-01T10:00:00Z"),
            track("never", 2, null),
            track("also-old-ish", 2, "2021-01-01T10:00:00Z")
        )

        val ordered = PlayerConnection.smartOrder(items).map { it.id }

        // Never played (no timestamp -> 0) ranks oldest; the most recent last.
        assertEquals("never", ordered.first())
        assertEquals("recent", ordered.last())
    }

    @Test
    fun `about twenty percent of the slots are reserved for loved tracks`() {
        val items = (0 until 10).map { track("i$it", it) }

        val ordered = PlayerConnection.smartOrder(items).map { it.id }

        // 10 items -> 2 loved (i8, i9); 8 fresh with step 4 -> i8 lands at index
        // 4 and i9 at the end, i.e. steadily spread rather than in a block.
        assertEquals(listOf("i0", "i1", "i2", "i3", "i8", "i4", "i5", "i6", "i7", "i9"), ordered)
    }

    @Test
    fun `every item survives reordering exactly once`() {
        val items = (0 until 7).map { track("i$it", it % 3) }

        val ordered = PlayerConnection.smartOrder(items).map { it.id }

        assertEquals(7, ordered.size)
        assertEquals(ordered.toSet().size, ordered.size)
        assertEquals(items.map { it.id }.toSet(), ordered.toSet())
    }

    private fun track(id: String, playCount: Int, lastPlayed: String? = null) =
        Fixtures.item(id, name = id, type = "Audio", playCount = playCount, lastPlayedDate = lastPlayed).toDomainItem() as Track
}
