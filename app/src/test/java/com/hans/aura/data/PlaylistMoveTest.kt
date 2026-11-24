package com.hans.aura.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.HttpException

/**
 * Playlist moves must resolve their destination against the server order as
 * it is when the write runs, not against the list the menu was opened on.
 *
 * A duplicate removal rewrites the whole playlist under the per-playlist
 * write gate. A move queued behind it used to resolve the destination from
 * the cache before waiting, so the write used a stale absolute index. These
 * tests pin both halves of the fix: the destination is read inside the gate,
 * after the removal completed, and it maps Audio rows around any hidden
 * (non-Audio) entries the server keeps in the playlist.
 */
@RunWith(RobolectricTestRunner::class)
class PlaylistMoveTest {

    private val server = MockWebServer()
    private lateinit var client: JellyfinClient
    private lateinit var repository: MediaRepository

    private val releaseRemoval = CountDownLatch(1)

    private data class Entry(val id: String, val type: String)

    @Before
    fun setUp() {
        server.start()
        client = JellyfinClient(ApplicationProvider.getApplicationContext<Context>())
        client.auth.save(Session(server.url("/").toString(), "user", "test", "token", "device"))
        repository = MediaRepository(client, { client.auth.session })
        LibraryCache.clear()
    }

    @After
    fun tearDown() {
        releaseRemoval.countDown()
        client.auth.clear()
        LibraryCache.clear()
        server.shutdown()
    }

    private fun itemsBody(entries: List<Entry>): String {
        val items = entries.joinToString(",") {
            """{"Id":"${it.id}","Type":"${it.type}","PlaylistItemId":"${it.id}"}"""
        }
        return """{"Items":[$items]}"""
    }

    private fun itemIdsBody(entries: List<Entry>): String =
        """{"ItemIds":[${entries.joinToString(",") { "\"${it.id}\"" }}]}"""

    @Test
    fun `visible index maps through audio rows to the absolute server index`() = runBlocking {
        server.enqueue(MockResponse().setBody(itemsBody(listOf(
            Entry("v", "Video"),
            Entry("a", "Audio"),
            Entry("v2", "Video"),
            Entry("b", "Audio")
        ))))
        server.enqueue(MockResponse().setResponseCode(204))

        // Visible rows are a (0) and b (1); b is the fourth server entry.
        repository.movePlaylistItemToVisibleIndex("p", "b", 1)

        val read = server.takeRequest()
        assertEquals("GET", read.method)
        assertEquals("/Playlists/p/Items?userId=user", read.path)
        val move = server.takeRequest()
        assertEquals("POST", move.method)
        assertEquals("/Playlists/p/Items/b/Move/3", move.path)
    }

    @Test
    fun `a missing visible row is rejected without a write`() = runBlocking {
        LibraryCache.put("col:p", listOf(JellyfinItem(id = "a", type = "Audio")))
        server.enqueue(MockResponse().setBody(itemsBody(listOf(
            Entry("v", "Video"),
            Entry("a", "Audio")
        ))))

        val error = runCatching {
            repository.movePlaylistItemToVisibleIndex("p", "a", 1)
        }.exceptionOrNull()

        assertTrue("expected a missing-row failure, got $error", error is IllegalStateException)
        assertEquals("Playlist row no longer exists", error?.message)
        assertEquals("no write may be sent", 1, server.requestCount)
        assertNotNull("no write happened, so the cache is still valid", LibraryCache.peek("col:p"))
    }

    @Test
    fun `a rejected move surfaces the status and evicts the list`() = runBlocking {
        LibraryCache.put("col:p", listOf(JellyfinItem(id = "a", type = "Audio")))
        server.enqueue(MockResponse().setBody(itemsBody(listOf(Entry("a", "Audio")))))
        server.enqueue(MockResponse().setResponseCode(403))

        val error = runCatching {
            repository.movePlaylistItemToVisibleIndex("p", "a", 0)
        }.exceptionOrNull()

        assertTrue("expected an HTTP failure, got $error", error is HttpException)
        assertEquals(403, (error as HttpException).code())
        assertEquals("/Playlists/p/Items?userId=user", server.takeRequest().path)
        assertEquals("/Playlists/p/Items/a/Move/0", server.takeRequest().path)
        assertNull("a rejected move must not leave the old order cached", LibraryCache.peek("col:p"))
    }

    @Test
    fun `a move queues behind a duplicate removal and resolves after it`() = runBlocking {
        val entries = CopyOnWriteArrayList(listOf(
            Entry("A", "Audio"),
            Entry("A", "Audio"),
            Entry("V", "Video"),
            Entry("B", "Audio"),
            Entry("C", "Audio")
        ))
        val removalStarted = CountDownLatch(1)
        val removalResponseAt = AtomicLong(0)
        val moveArrived = CountDownLatch(1)
        val moveArrivedAt = AtomicLong(0)
        val moveIndex = AtomicInteger(-1)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    request.method == "GET" && path.startsWith("/Playlists/p/Items") ->
                        MockResponse().setBody(itemsBody(entries.toList()))

                    request.method == "GET" && path == "/Playlists/p" ->
                        MockResponse().setBody(itemIdsBody(entries.toList()))

                    // The duplicate removal's rewrite. Hold the response open
                    // until the test has observed that the move is queued.
                    request.method == "POST" && path == "/Playlists/p" -> {
                        removalStarted.countDown()
                        releaseRemoval.await(10, TimeUnit.SECONDS)
                        synchronized(entries) {
                            entries.removeAt(entries.indexOfFirst { it.id == "A" })
                        }
                        removalResponseAt.set(System.nanoTime())
                        MockResponse().setResponseCode(204)
                    }

                    request.method == "POST" && path.startsWith("/Playlists/p/Items/C/Move/") -> {
                        moveArrivedAt.set(System.nanoTime())
                        moveIndex.set(path.substringAfterLast('/').toInt())
                        moveArrived.countDown()
                        MockResponse().setResponseCode(204)
                    }

                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val removal = async(Dispatchers.IO) { repository.removeFromPlaylist("p", "A", 0) }
        assertTrue("removal must reach its write", removalStarted.await(5, TimeUnit.SECONDS))

        // A menu opened before the removal started can still invoke a move.
        // It must neither resolve its destination nor reach the server while
        // the removal owns the playlist.
        val move = async(Dispatchers.IO) {
            repository.movePlaylistItemToVisibleIndex("p", "C", 1)
        }
        assertFalse(
            "the move must not be dispatched while the removal is in flight",
            moveArrived.await(500, TimeUnit.MILLISECONDS)
        )

        releaseRemoval.countDown()
        removal.await()
        assertTrue("the queued move must run after the removal", moveArrived.await(5, TimeUnit.SECONDS))
        move.await()

        assertTrue(
            "the move was dispatched before the removal's response",
            moveArrivedAt.get() > removalResponseAt.get()
        )
        // Post-removal entries are A, V, B, C: Audio rows map visible 1 to
        // absolute index 2. Against the stale pre-removal list (A, A, V, B, C)
        // the same row would map to absolute index 1.
        assertEquals(2, moveIndex.get())
    }
}
