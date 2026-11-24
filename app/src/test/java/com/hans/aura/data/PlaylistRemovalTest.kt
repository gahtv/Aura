package com.hans.aura.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.HttpException

@RunWith(RobolectricTestRunner::class)
class PlaylistRemovalTest {
    private val server = MockWebServer()
    private lateinit var client: JellyfinClient

    @Before
    fun setUp() {
        server.start()
        client = JellyfinClient(ApplicationProvider.getApplicationContext<Context>())
        client.auth.save(Session(server.url("/").toString(), "user", "test", "token", "device"))
        MusicSources.install(JellyfinSource(client, MediaRepository(client)))
    }

    @After
    fun tearDown() {
        client.auth.clear()
        MusicSources.reset()
        server.shutdown()
    }

    /**
     * Jellyfin 10.11 fills `PlaylistItemId` from the linked child's `ItemId`,
     * so two copies of track A both report "A". DELETE ?entryIds=A removes
     * every child whose ItemId matches (PlaylistManager), which is exactly why
     * duplicates must go through the full-list update path.
     */
    private fun playlist(vararg ids: String) {
        val items = ids.joinToString(",") { """{"Id":"$it","PlaylistItemId":"$it"}""" }
        server.enqueue(MockResponse().setBody("""{"Items":[$items]}"""))
    }

    private fun assertRefetch() {
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/Playlists/p/Items?userId=user", request.path)
    }

    private fun allEntries(vararg ids: String) {
        val items = ids.joinToString(",") { "\"$it\"" }
        server.enqueue(MockResponse().setBody("""{"ItemIds":[$items]}"""))
    }

    private fun assertUnfilteredRead() {
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/Playlists/p", request.path)
    }

    private fun assertEditFailure(
        error: Throwable?,
        outcome: PlaylistEditOutcome,
        causeType: Class<out Throwable>
    ) {
        assertTrue("expected a PlaylistEditException, got $error", error is PlaylistEditException)
        val edit = error as PlaylistEditException
        assertEquals(outcome, edit.outcome)
        assertTrue(
            "expected a ${causeType.simpleName} cause, got ${edit.cause}",
            causeType.isInstance(edit.cause)
        )
    }

    @Test
    fun `single copy refetches then deletes the fresh entry identity`() = runBlocking {
        playlist("A", "B")
        server.enqueue(MockResponse().setResponseCode(204))
        SourceQueries.removeFromPlaylist("p", "A", 0)
        assertRefetch()
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("A", request.requestUrl!!.queryParameter("entryIds"))
        assertNull(request.requestUrl!!.queryParameter("Ids"))
    }

    @Test
    fun `middle A among duplicates is removed by rewriting the list, never by entry id`() = runBlocking {
        // Every duplicate A row reports PlaylistItemId "A"; a DELETE by that
        // entry id would remove all three As (the reported Devin bug). Only
        // the update path can drop the one copy the ordinal names.
        playlist("A", "B", "A", "C", "A")
        allEntries("A", "B", "A", "C", "A")
        server.enqueue(MockResponse().setResponseCode(204))
        SourceQueries.removeFromPlaylist("p", "A", 1)
        assertRefetch()
        assertUnfilteredRead()
        val request = server.takeRequest()
        assertEquals("no DELETE-by-entry may be sent for duplicate rows", 3, server.requestCount)
        assertEquals("POST", request.method)
        assertEquals("/Playlists/p", request.path)
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(setOf("Ids"), body.keys)
        assertEquals(listOf("A", "B", "C", "A"), body.getValue("Ids").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `hidden playlist entries prevent replacing visible duplicates`() = runBlocking {
        playlist("A", "B", "A", "B")
        server.enqueue(MockResponse().setBody("""{"ItemIds":["hidden","A","B","A","B"]}"""))
        val error = runCatching { SourceQueries.removeFromPlaylist("p", "A", 1) }.exceptionOrNull()
        assertEquals(
            "This playlist has tracks you can't see, so one copy can't be removed safely",
            error?.message
        )
        assertEditFailure(error, PlaylistEditOutcome.FAILED, IllegalStateException::class.java)
        assertRefetch()
        assertUnfilteredRead()
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `unavailable unfiltered entries prevent writing`() = runBlocking {
        playlist("A", "A")
        server.enqueue(MockResponse().setResponseCode(403))
        assertEditFailure(
            runCatching { SourceQueries.removeFromPlaylist("p", "A", 1) }.exceptionOrNull(),
            PlaylistEditOutcome.FAILED,
            HttpException::class.java
        )
        assertRefetch()
        assertUnfilteredRead()
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `stale occurrence including an empty playlist fails without writing`() = runBlocking {
        for (ids in listOf(arrayOf("A", "B"), emptyArray())) {
            playlist(*ids)
            val error = runCatching { SourceQueries.removeFromPlaylist("p", "A", 1) }.exceptionOrNull()
            assertEditFailure(error, PlaylistEditOutcome.FAILED, IllegalStateException::class.java)
            assertRefetch()
        }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `invalid row identity fails without any request`() = runBlocking {
        assertEditFailure(
            runCatching { SourceQueries.removeFromPlaylist("p", "", 0) }.exceptionOrNull(),
            PlaylistEditOutcome.FAILED,
            IllegalArgumentException::class.java
        )
        assertEditFailure(
            runCatching { SourceQueries.removeFromPlaylist("p", "A", -1) }.exceptionOrNull(),
            PlaylistEditOutcome.FAILED,
            IllegalArgumentException::class.java
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a lost read response is a definite failure and keeps the cache`() = runBlocking {
        LibraryCache.put("col:p", listOf(JellyfinItem(id = "A", type = "Audio")))
        // Malformed status line, same as below: the GET reaches the server but
        // no write does, so the visible list is still valid.
        server.enqueue(MockResponse().setStatus("BOGUS"))

        val error = runCatching { SourceQueries.removeFromPlaylist("p", "A", 0) }.exceptionOrNull()
        assertEditFailure(error, PlaylistEditOutcome.FAILED, IOException::class.java)
        assertEquals("only the pre-read may be sent", 1, server.requestCount)
        assertNotNull("no write was attempted: col:<playlistId> stays valid", LibraryCache.peek("col:p"))
    }

    @Test
    fun `a lost write response surfaces as an unknown edit and evicts the list`() = runBlocking {
        LibraryCache.put("col:p", listOf(JellyfinItem(id = "A", type = "Audio")))
        assertNotNull("precondition: the stale list is cached", LibraryCache.peek("col:p"))

        playlist("A", "A", "B")
        allEntries("A", "A", "B")
        // Malformed status line: the request is read, but no valid response
        // reaches the client. ProtocolException is an IOException and, unlike
        // EOF, is not retried by OkHttp, so this is deterministic.
        server.enqueue(MockResponse().setStatus("BOGUS"))

        val error = runCatching { SourceQueries.removeFromPlaylist("p", "A", 1) }.exceptionOrNull()
        assertEditFailure(error, PlaylistEditOutcome.UNKNOWN, IOException::class.java)
        assertNull("a lost response must evict col:<playlistId>", LibraryCache.peek("col:p"))
    }

    @Test
    fun `a write with an unknown outcome evicts the cached playlist`() = runBlocking {
        LibraryCache.put("col:p", listOf(JellyfinItem(id = "A", type = "Audio")))
        assertNotNull("precondition: the stale list is cached", LibraryCache.peek("col:p"))

        playlist("A", "A", "B")
        allEntries("A", "A", "B")
        server.enqueue(MockResponse().setResponseCode(500))
        assertEditFailure(
            runCatching { SourceQueries.removeFromPlaylist("p", "A", 1) }.exceptionOrNull(),
            PlaylistEditOutcome.UNKNOWN,
            HttpException::class.java
        )

        // A 5xx may hide a committed edit: the next read must reconcile with
        // the server instead of serving the pre-edit order.
        assertNull("unknown outcome must evict col:<playlistId>", LibraryCache.peek("col:p"))
    }

    @Test
    fun `failed reads and both failed writes propagate as typed failures`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403))
        assertEditFailure(
            runCatching { SourceQueries.removeFromPlaylist("p", "A", 0) }.exceptionOrNull(),
            PlaylistEditOutcome.FAILED,
            HttpException::class.java
        )
        assertRefetch()
        for (ids in listOf(arrayOf("A"), arrayOf("A", "A"))) {
            playlist(*ids)
            if (ids.size > 1) allEntries(*ids)
            server.enqueue(MockResponse().setResponseCode(403))
            assertEditFailure(
                runCatching { SourceQueries.removeFromPlaylist("p", "A", 0) }.exceptionOrNull(),
                PlaylistEditOutcome.FAILED,
                HttpException::class.java
            )
            assertRefetch()
            if (ids.size > 1) assertUnfilteredRead()
            server.takeRequest()
        }
        assertEquals(6, server.requestCount)
    }
}
