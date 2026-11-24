package com.hans.aura.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The duplicate-removal path rewrites the whole playlist id list. An add that
 * commits between its read and its PUT is erased by that rewrite, so the
 * repository must hold the playlist's write gate across the entire operation.
 *
 * The dispatcher below delays the removal's PUT and records arrival times for
 * both requests: with the gate, the add cannot reach the server until the
 * removal call has returned; without it, the add arrives while the PUT is
 * still sleeping.
 */
@RunWith(RobolectricTestRunner::class)
class PlaylistWriteSerializationTest {

    private val server = MockWebServer()
    private lateinit var client: JellyfinClient
    private lateinit var repository: MediaRepository

    private val updateStarted = CountDownLatch(1)
    private val addArrived = CountDownLatch(1)
    private val otherAddArrived = CountDownLatch(1)
    private val updateReturnedAt = AtomicLong(0)
    private val addArrivedAt = AtomicLong(0)
    private val otherAddArrivedAt = AtomicLong(0)

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    request.method == "GET" && path.startsWith("/Playlists/p/Items") ->
                        MockResponse().setBody(
                            """{"Items":[""" +
                                """{"Id":"A","PlaylistItemId":"A"},""" +
                                """{"Id":"A","PlaylistItemId":"A"},""" +
                                """{"Id":"B","PlaylistItemId":"B"}""" +
                                """]}"""
                        )

                    // Unfiltered view used by the duplicate path to prove no
                    // hidden entry can be dropped by the rewrite.
                    request.method == "GET" && path == "/Playlists/p" ->
                        MockResponse().setBody("""{"ItemIds":["A","A","B"]}""")

                    // The duplicate removal's rewrite: hold the response open.
                    request.method == "POST" && path == "/Playlists/p" -> {
                        updateStarted.countDown()
                        // Long enough that an ungated add would definitely
                        // arrive while this response is still held open.
                        Thread.sleep(1_000)
                        updateReturnedAt.set(System.nanoTime())
                        MockResponse().setResponseCode(204)
                    }

                    request.method == "POST" && path.startsWith("/Playlists/p/Items") -> {
                        addArrivedAt.set(System.nanoTime())
                        addArrived.countDown()
                        MockResponse().setResponseCode(204)
                    }

                    request.method == "POST" && path.startsWith("/Playlists/q/Items") -> {
                        otherAddArrivedAt.set(System.nanoTime())
                        otherAddArrived.countDown()
                        MockResponse().setResponseCode(204)
                    }

                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        client = JellyfinClient(ApplicationProvider.getApplicationContext<Context>())
        client.auth.save(Session(server.url("/").toString(), "user", "test", "token", "device"))
        repository = MediaRepository(client, { client.auth.session })
    }

    @After
    fun tearDown() {
        client.auth.clear()
        server.shutdown()
    }

    @Test
    fun `an add queues behind a duplicate removal of the same playlist`() = runBlocking {
        val removalDoneAt = AtomicLong(0)
        val removal = async(Dispatchers.IO) {
            repository.removeFromPlaylist("p", "A", 1)
            removalDoneAt.set(System.nanoTime())
        }
        assertTrue("removal must reach its write", updateStarted.await(5, TimeUnit.SECONDS))

        val add = async(Dispatchers.IO) { repository.addToPlaylist("p", listOf("C")) }
        assertTrue("add must eventually run", addArrived.await(5, TimeUnit.SECONDS))
        removal.await()
        add.await()

        assertTrue(
            "the add reached the server before the removal (and its lock) finished",
            addArrivedAt.get() > removalDoneAt.get()
        )
        assertTrue(
            "the add may only be sent after the delayed rewrite response",
            addArrivedAt.get() > updateReturnedAt.get()
        )
    }

    @Test
    fun `an add to a different playlist is not blocked by a removal`() = runBlocking {
        val removalDoneAt = AtomicLong(0)
        val removal = async(Dispatchers.IO) {
            repository.removeFromPlaylist("p", "A", 1)
            removalDoneAt.set(System.nanoTime())
        }
        assertTrue("removal must reach its write", updateStarted.await(5, TimeUnit.SECONDS))

        val add = async(Dispatchers.IO) { repository.addToPlaylist("q", listOf("C")) }
        assertTrue("unrelated add must run", otherAddArrived.await(5, TimeUnit.SECONDS))
        add.await()
        removal.await()
        assertTrue(
            "another playlist's write waited for the removal",
            otherAddArrivedAt.get() < removalDoneAt.get()
        )
        assertTrue(
            "the unrelated add arrived before the removal's delayed response",
            otherAddArrivedAt.get() < updateReturnedAt.get()
        )
    }
}
