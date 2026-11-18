package com.hans.aura.data

import androidx.test.core.app.ApplicationProvider
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SourceQueriesTest {
    private lateinit var server: ServerSocket
    private lateinit var serverThread: Thread
    private lateinit var client: JellyfinClient
    private val requests = LinkedBlockingQueue<HttpUrl>()
    private var status = 200
    private var body = """{"Items":[],"TotalRecordCount":0,"StartIndex":0}"""

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        Prefs.init(context)
        Prefs.knownLibraries = Fixtures.allLibraries
        Prefs.hiddenLibraries = emptySet()
        server = ServerSocket(0)
        serverThread = thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use {
                    val reader = it.getInputStream().bufferedReader()
                    val path = reader.readLine().split(" ")[1]
                    while (!reader.readLine().isNullOrEmpty()) { }
                    requests.add("http://localhost$path".toHttpUrl())
                    val bytes = body.toByteArray()
                    val headers = "HTTP/1.1 $status Response\r\nContent-Type: application/json\r\n" +
                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    it.getOutputStream().apply {
                        write(headers.toByteArray())
                        write(bytes)
                        flush()
                    }
                }
            }
        }
        client = JellyfinClient(context)
        client.auth.save(Session(
            serverUrl = "http://127.0.0.1:${server.localPort}", userId = "u",
            userName = "test", accessToken = "token", deviceId = "device"
        ))
        MusicSources.install(JellyfinSource(client, MediaRepository(client)))
    }

    @After
    fun tearDown() {
        server.close()
        serverThread.join(1000)
        client.auth.clear()
        MusicSources.reset()
    }

    @Test
    fun `album jump forwards letter fields image limit and page to Items`() = runTest {
        SourceQueries.items(
            includeItemTypes = "MusicAlbum", nameStartsWithOrGreater = "M",
            fields = "SortName", imageTypeLimit = 2, enableUserData = false,
            limit = 25, startIndex = 50
        )

        val request = requests.remove()
        assertEquals("/Users/u/Items", request.encodedPath)
        assertEquals("M", request.queryParameter("nameStartsWithOrGreater"))
        assertEquals("SortName", request.queryParameter("Fields"))
        assertEquals("2", request.queryParameter("ImageTypeLimit"))
        assertEquals("false", request.queryParameter("EnableUserData"))
        assertEquals("25", request.queryParameter("Limit"))
        assertEquals("50", request.queryParameter("StartIndex"))
    }

    @Test
    fun `album jump forwards letter through visible library fan out`() = runTest {
        Prefs.hiddenLibraries = setOf("lib-music")

        SourceQueries.items(includeItemTypes = "MusicAlbum", nameStartsWithOrGreater = "Z")

        assertEquals(2, requests.size)
        val seen = requests.toList()
        assertEquals(setOf("lib-atmos", "lib-mixed"), seen.map { it.queryParameter("ParentId") }.toSet())
        assertTrue(seen.all { it.queryParameter("nameStartsWithOrGreater") == "Z" })
    }

    @Test
    fun `artists uses album artists endpoint with sorting and paging`() = runTest {
        SourceQueries.artists(sortBy = "DateCreated", sortOrder = "Descending", limit = 20, startIndex = 40)

        val request = requests.remove()
        assertEquals("/Artists/AlbumArtists", request.encodedPath)
        assertEquals("DateCreated", request.queryParameter("SortBy"))
        assertEquals("Descending", request.queryParameter("SortOrder"))
        assertEquals("20", request.queryParameter("Limit"))
        assertEquals("40", request.queryParameter("StartIndex"))
    }

    @Test
    fun `artists fans out only to visible music libraries and merges page`() = runTest {
        Prefs.hiddenLibraries = setOf("lib-music")
        body = """{"Items":[{"Id":"a","Name":"A"},{"Id":"b","Name":"B"}],"TotalRecordCount":2}"""

        val result = SourceQueries.artists(sortOrder = "Descending", limit = 1, startIndex = 1)

        assertEquals(listOf("a"), result.items.map { it.id })
        assertEquals(2, requests.size)
        val seen = requests.toList()
        assertEquals(setOf("lib-atmos", "lib-mixed"), seen.map { it.queryParameter("ParentId") }.toSet())
        assertTrue(seen.all { it.encodedPath == "/Artists/AlbumArtists" })
        assertTrue(seen.all { it.queryParameter("Limit") == "2" && it.queryParameter("StartIndex") == "0" })
    }

    @Test
    fun `all libraries hidden returns no artists without a request`() = runTest {
        Prefs.hiddenLibraries = Fixtures.allLibraries.map { it.id }.toSet()

        assertTrue(SourceQueries.artists().items.isEmpty())
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `unreadable libraries return an empty artists page`() = runTest {
        Prefs.hiddenLibraries = setOf("lib-music")
        status = 500

        assertTrue(SourceQueries.artists().items.isEmpty())
        assertEquals(2, requests.size)
        assertTrue(requests.all { it.encodedPath == "/Artists/AlbumArtists" })
    }
}
