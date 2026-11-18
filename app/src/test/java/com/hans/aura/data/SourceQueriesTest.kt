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
import org.junit.Assert.assertNull
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
        Prefs.lyricsWebFallback = false
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
    fun `jellyfin caches only bios and strips html`() = runTest {
        body = """{"Id":"bio","Name":"Artist","Overview":"<p> First &amp; second<br>Third</p><p>Last</p>"}"""
        val source = MusicSources.current!!
        source.item("bio")
        assertEquals("First & second\nThird\nLast", source.artistBio("bio"))
        assertEquals(source.artistBio("bio"), source.albumBio("bio"))
        assertEquals(1, requests.size)
        body = """{"Id":"blank","Name":"Blank","Overview":"<p> </p>"}"""
        assertNull(source.artistBio("blank"))
        assertNull(source.albumBio("blank"))
        assertEquals(2, requests.size)
        body = """{"Id":"bio","Name":"Artist","UserData":{"IsFavorite":true,"PlayCount":5,"LastPlayedDate":"2026-01-01","PlaybackPositionTicks":12345}}"""
        val refreshed = source.item("bio")!!
        assertEquals(true, refreshed.userData!!.isFavorite)
        assertEquals(5, refreshed.userData!!.playCount)
        assertEquals("2026-01-01", refreshed.userData!!.lastPlayedDate)
        assertEquals(12345L, refreshed.userData!!.playbackPositionTicks)
        assertEquals(3, requests.size)
        source.item("bio")
        assertEquals(4, requests.size)
    }

    @Test
    fun `jellyfin timed lyrics use ticks and line karaoke`() = runTest {
        body = """{"Lyrics":[{"Text":"First","Start":123400000},{"Text":"Second","Start":150000000}]}"""
        val lines = MusicSources.current!!.lyricsTimed("song")!!
        assertEquals(listOf(12340L, 15000L), lines.map { it.startMs })
        assertTrue(lines.all { it.words.isEmpty() })
        assertEquals(listOf(KaraokeSpan("First", false)), karaokeSpans(lines[0], 12339))
        assertEquals(listOf(KaraokeSpan("First", true)), karaokeSpans(lines[0], 12340))
        LyricsStore.fetchPreferred(client.api, "song")
        assertEquals(1, requests.size)
        assertEquals("/Audio/song/lyrics", requests.single().encodedPath)
    }

    @Test
    fun `unsynced lyrics stay plain and do not query web`() = runTest {
        Prefs.lyricsWebFallback = true
        body = """{"LyricText":"First line\nSecond line"}"""
        val source = JellyfinSource(client, MediaRepository(client), lookupLyrics = { error("unexpected web") })
        MusicSources.install(source)
        assertNull(source.lyricsTimed("plain"))
        val plain = LyricsStore.fetchPreferred(client.api, "plain")
        assertEquals(2, plain.size)
        assertTrue(plain.none { it.isSynced })
        assertEquals(1, requests.size)
    }

    @Test
    fun `empty jellyfin lyrics only use web when enabled and expose origin`() = runTest {
        body = """{"Id":"song","Name":"Track"}"""
        var lookups = 0
        val source = JellyfinSource(client, MediaRepository(client), fetchLyrics = { emptyList() }, lookupLyrics = {
            assertEquals("song", it.id)
            lookups++
            LyricsStore.parseLrcText("[00:12.34]Web line")
        })
        assertNull(source.lyricsTimed("song"))
        assertEquals(0, lookups)
        assertTrue(requests.isEmpty())
        Prefs.lyricsWebFallback = true
        assertEquals(listOf(TimedLyricLine(12340, "Web line")), source.lyricsTimed("song"))
        assertEquals(true, source.cachedWebLyricsFound("song"))
        source.lyricsTimed("song")
        assertEquals(2, lookups)
        assertTrue(source.hasPlayHistory)
        assertEquals("Audio", source.playHistoryItemType)
        source.recordPlay("song")
        assertEquals(2, requests.size)
        Prefs.lyricsWebFallback = false
        assertNull(source.lyricsTimed("song"))
        assertNull(source.cachedWebLyricsFound("song"))
    }

    @Test
    fun `empty lyrics and web misses are retried`() = runTest {
        body = "{}"
        val source = MusicSources.current!!
        assertNull(source.lyricsTimed("song"))
        status = 500
        assertNull(source.lyricsTimed("song"))
        status = 200
        body = """{"LyricText":"[00:01.00]Recovered"}"""
        assertEquals(listOf(TimedLyricLine(1000, "Recovered")), source.lyricsTimed("song"))
        assertEquals(3, requests.size)

        body = """{"Id":"web","Name":"Track"}"""
        Prefs.lyricsWebFallback = true
        var lookups = 0
        val webSource = JellyfinSource(client, MediaRepository(client), fetchLyrics = { emptyList() }, lookupLyrics = {
            lookups++
            if (lookups == 1) emptyList() else LyricsStore.parseLrcText("[00:02]Recovered web")
        })
        assertNull(webSource.lyricsTimed("web"))
        assertEquals(false, webSource.cachedWebLyricsFound("web"))
        assertEquals(listOf(TimedLyricLine(2000, "Recovered web")), webSource.lyricsTimed("web"))
        assertEquals(true, webSource.cachedWebLyricsFound("web"))
        assertEquals(2, lookups)
    }

    @Test
    fun `missing lyrics and server errors return null`() = runTest {
        body = "{}"
        assertNull(MusicSources.current!!.lyricsTimed("empty"))
        status = 500
        assertNull(MusicSources.current!!.lyricsTimed("failed"))
        assertNull(MusicSources.current!!.albumBio("failed"))
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
