package com.hans.aura.ui.common

import androidx.test.core.app.ApplicationProvider
import com.hans.aura.AuraApp
import com.hans.aura.data.LibraryCache
import com.hans.aura.data.PlaylistEditOutcome
import com.hans.aura.data.Session
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The playlist screen hides move actions while an edit is in flight, but a
 * menu opened just before that edit started is still on screen. Invoking its
 * Move row must be refused at execution time instead of resolving a stale row
 * ordinal against a list the server has already changed.
 */
@RunWith(RobolectricTestRunner::class)
class ContextMenusPlaylistMoveTest {

    private val server = MockWebServer()
    private lateinit var app: AuraApp

    @Before
    fun setUp() {
        server.start()
        app = ApplicationProvider.getApplicationContext()
        app.jellyfin.auth.save(Session(server.url("/").toString(), "u", "Test", "token", "device"))
        LibraryCache.clear()
    }

    @After
    fun tearDown() {
        app.container.mutations.reset()
        app.jellyfin.auth.clear()
        LibraryCache.clear()
        server.shutdown()
    }

    @Test
    fun `a move invoked after an edit started is refused without a request`() = runBlocking {
        // A guard response: if the refusal were missing, the repository would
        // fail fast on this instead of blocking on an empty queue.
        server.enqueue(MockResponse().setResponseCode(404))
        val token = app.container.mutations.beginPlaylistEdit("p")
        var moved = false

        ContextMenus.performPlaylistMove(
            app,
            ContextMenus.PlaylistMove(
                playlistId = "p",
                itemId = "A",
                index = 0,
                size = 2,
                onMoved = { moved = true }
            ),
            1
        )

        assertFalse("a pending edit must refuse the move", moved)
        assertEquals("the refused move must not reach the server", 0, server.requestCount)
        app.container.mutations.finishPlaylistEdit("p", token, PlaylistEditOutcome.FAILED)
    }

    @Test
    fun `a move whose visible row vanished refreshes the stale screen`() = runBlocking {
        // The server list no longer has row 5; the repository rejects the
        // move before any write. The screen must still be refreshed so the
        // user sees the current order instead of a silent no-op.
        server.enqueue(
            MockResponse().setBody(
                """{"Items":[{"Id":"A","Type":"Audio","PlaylistItemId":"A"}]}"""
            )
        )
        var moved = false

        ContextMenus.performPlaylistMove(
            app,
            // A menu built when the playlist had six rows: row 5 existed
            // then, but the server list has shrunk before this action ran.
            ContextMenus.PlaylistMove(
                playlistId = "p",
                itemId = "A",
                index = 3,
                size = 6,
                onMoved = { moved = true }
            ),
            5
        )

        assertTrue("a rejected move must refresh the stale screen", moved)
        assertEquals("only the reconciling read must reach the server", 1, server.requestCount)
    }
}
