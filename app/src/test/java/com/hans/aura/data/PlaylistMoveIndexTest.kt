package com.hans.aura.data

import androidx.test.core.app.ApplicationProvider
import com.hans.aura.AuraApp
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaylistMoveIndexTest {
    @Before
    fun setUp() {
        LibraryCache.clear()
        ApplicationProvider.getApplicationContext<AuraApp>()
        val delegate = MusicSources.current!!
        MusicSources.install(object : MusicSource by delegate {
            override suspend fun playlistItems(playlistId: String): List<JellyfinItem> = listOf(
                JellyfinItem(id = "hidden", type = "Video"),
                JellyfinItem(id = "a", type = "Audio"),
                JellyfinItem(id = "b", type = "Audio")
            )
        })
    }

    @After
    fun tearDown() {
        MusicSources.reset()
        LibraryCache.clear()
    }

    @Test
    fun `visible playlist target maps around hidden non audio entries`() = runBlocking {
        assertEquals(1, SourceQueries.playlistMoveIndex("p", 0))
        assertEquals(2, SourceQueries.playlistMoveIndex("p", 1))
        assertNull(SourceQueries.playlistMoveIndex("p", 2))
    }
}
