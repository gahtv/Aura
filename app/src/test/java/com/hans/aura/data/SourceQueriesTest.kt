package com.hans.aura.data

import androidx.test.core.app.ApplicationProvider
import com.hans.aura.AuraApp
import com.hans.aura.ui.home.HomeUiState
import com.hans.aura.ui.home.HomeViewModel
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
    private var respond: ((HttpUrl) -> Pair<Int, String>)? = null

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
                    val url = "http://localhost$path".toHttpUrl()
                    requests.add(url)
                    val (code, payload) = respond?.invoke(url) ?: (status to body)
                    val bytes = payload.toByteArray()
                    val headers = "HTTP/1.1 $code Response\r\nContent-Type: application/json\r\n" +
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
    fun `home recently played tracks request only played items`() {
        loadHome()

        val request = requests.single {
            it.queryParameter("IncludeItemTypes") == "Audio" && it.queryParameter("SortBy") == "DatePlayed" &&
                it.queryParameter("Limit") == "20"
        }
        assertEquals("DatePlayed", request.queryParameter("SortBy"))
        assertEquals("Descending", request.queryParameter("SortOrder"))
        assertEquals("20", request.queryParameter("Limit"))
        assertEquals("IsPlayed", request.queryParameter("Filters"))
        assertTrue(requests.filter { it.queryParameter("SortBy") == "DatePlayed" }
            .all { it.queryParameter("Filters") == "IsPlayed" })
    }

    @Test
    fun `home recent artists come only from played tracks deduped in recency order`() {
        historyResponses(listOf(
            historyTrack("new", "b", "a"),
            historyTrack("repeat", "b"),
            JellyfinItem(id = "old", albumArtists = listOf(NameId(id = "c"))),
            JellyfinItem(id = "unknown")
        ))

        val state = loadHome()

        assertEquals(listOf("b", "a", "c"), state.recentArtists!!.map { it.id })
        assertEquals("b,a,c", requests.single { it.queryParameter("Ids") != null }.queryParameter("Ids"))
        assertEquals(2, requests.count { it.queryParameter("SortBy") == "DatePlayed" })
    }

    @Test
    fun `home recent artists load independently of the cached track row`() {
        val tracks = listOf(historyTrack("cached", "b", "a"))
        historyResponses(listOf(historyTrack("latest", "c", "b")))

        val state = loadHome(cachedRecent = tracks)

        assertEquals(tracks, state.recentlyPlayed)
        assertEquals(listOf("c", "b"), state.recentArtists!!.map { it.id })
        assertEquals("100", requests.single { it.queryParameter("SortBy") == "DatePlayed" }
            .queryParameter("Limit"))
    }

    @Test
    fun `home caps history rows and hydrates only twenty distinct artists`() {
        historyResponses(List(25) { historyTrack("repeat$it", "a0") } +
            (1 until 25).map { historyTrack("t$it", "a$it") })

        val state = loadHome()

        assertEquals(20, state.recentlyPlayed!!.size)
        assertEquals((0 until 20).map { "a$it" }, state.recentArtists!!.map { it.id })
        assertTrue(requests.any { it.queryParameter("SortBy") == "DatePlayed" &&
            it.queryParameter("Limit")!!.toInt() > 20 })
    }

    @Test
    fun `home preserves non Jellyfin history queries`() {
        val queries = mutableListOf<LibraryFilter.ItemsQuery>()
        val source = MusicSources.current!!
        MusicSources.install(object : MusicSource by source {
            override val id = "other"
            override suspend fun recentlyPlayed(limit: Int) = super<MusicSource>.recentlyPlayed(limit)
            override suspend fun recentArtists(limit: Int) = super<MusicSource>.recentArtists(limit)
            override suspend fun browse(query: LibraryFilter.ItemsQuery, cachePrefix: String?): ItemsResponse {
                queries.add(query)
                return ItemsResponse(items = when (query.includeItemTypes) {
                    "Audio" -> listOf(JellyfinItem(id = "track"))
                    "MusicArtist" -> listOf(JellyfinItem(id = "artist"))
                    else -> emptyList()
                })
            }
            override suspend fun artists(sortBy: String, sortOrder: String, limit: Int, startIndex: Int) = ItemsResponse()
            override suspend fun atmosAlbumIds(): Set<String> = emptySet()
        })

        val state = loadHome()

        assertEquals(listOf("artist"), state.recentArtists!!.map { it.id })
        assertTrue(queries.all { it.ids == null && it.filters == null })
        assertEquals(2, queries.count { it.sortBy == "DatePlayed" && it.limit == 20 })
    }

    @Test
    fun `home artist hydration failure keeps played tracks and empties artists`() {
        historyResponses(listOf(historyTrack("played", "a")), hydrationStatus = 500)

        val state = loadHome()

        assertEquals(listOf("played"), state.recentlyPlayed!!.map { it.id })
        assertEquals(emptyList<JellyfinItem>(), state.recentArtists)
        assertNull(state.error)
    }

    private fun historyTrack(id: String, vararg artists: String) =
        JellyfinItem(id = id, artistItems = artists.map { NameId(id = it) })

    private fun historyResponses(tracks: List<JellyfinItem>, hydrationStatus: Int = 200) {
        respond = { url ->
            val ids = url.queryParameter("Ids")?.split(",")
            val items = when {
                ids != null -> ids.reversed().map { JellyfinItem(id = it, type = "MusicArtist") }
                url.queryParameter("IncludeItemTypes") == "Audio" &&
                    url.queryParameter("SortBy") == "DatePlayed" -> tracks
                url.queryParameter("IncludeItemTypes") == "MusicArtist" -> listOf(JellyfinItem(id = "unplayed"))
                else -> emptyList()
            }.take(url.queryParameter("Limit")?.toInt() ?: 100)
            (if (ids != null) hydrationStatus else 200) to
                Json.encodeToString(ItemsResponse(items = items, totalRecordCount = items.size))
        }
    }

    @Test
    fun `home empty play history leaves both history shelves empty`() {
        val state = loadHome()

        assertEquals(emptyList<JellyfinItem>(), state.recentlyPlayed)
        assertEquals(emptyList<JellyfinItem>(), state.recentArtists)
        assertNull(state.error)
        assertTrue(requests.none {
            it.queryParameter("IncludeItemTypes") == "MusicArtist" || it.queryParameter("Ids") != null
        })
    }

    @Test
    fun `home history request failures leave both history shelves empty`() {
        respond = { url -> (if (url.queryParameter("SortBy") == "DatePlayed") 500 else 200) to body }

        val state = loadHome()

        assertEquals(emptyList<JellyfinItem>(), state.recentlyPlayed)
        assertEquals(emptyList<JellyfinItem>(), state.recentArtists)
        assertNull(state.error)
    }

    @Test
    fun `history seam returns empty without an installed source`() = runTest {
        MusicSources.reset()

        assertTrue(SourceQueries.recentlyPlayed().isEmpty())
        assertTrue(SourceQueries.recentArtists().isEmpty())
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `history seam contains failures from a source`() = runTest {
        val source = MusicSources.current!!
        MusicSources.install(object : MusicSource by source {
            override suspend fun recentlyPlayed(limit: Int): List<JellyfinItem> = error("unavailable")
            override suspend fun recentArtists(limit: Int): List<JellyfinItem> = error("unavailable")
        })

        assertTrue(SourceQueries.recentlyPlayed().isEmpty())
        assertTrue(SourceQueries.recentArtists().isEmpty())
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `generic album history keeps its item type and has no artist history`() = runTest {
        val queries = mutableListOf<LibraryFilter.ItemsQuery>()
        val source = object : MusicSource by MusicSources.current!! {
            override val playHistoryItemType = "MusicAlbum"
            override suspend fun recentlyPlayed(limit: Int) = super<MusicSource>.recentlyPlayed(limit)
            override suspend fun recentArtists(limit: Int) = super<MusicSource>.recentArtists(limit)
            override suspend fun browse(query: LibraryFilter.ItemsQuery, cachePrefix: String?): ItemsResponse {
                queries.add(query)
                return ItemsResponse(items = listOf(JellyfinItem(id = "album")))
            }
        }
        MusicSources.install(source)

        assertEquals(listOf("album"), SourceQueries.recentlyPlayed(limit = 5).map { it.id })
        assertTrue(SourceQueries.recentArtists(limit = 5).isEmpty())
        assertEquals("MusicAlbum", queries.single().includeItemTypes)
        assertEquals("DatePlayed", queries.single().sortBy)
        assertEquals("Descending", queries.single().sortOrder)
        assertEquals(5, queries.single().limit)
        assertNull(queries.single().filters)
    }

    @Test
    fun `generic history contains browse failures and skips unsupported history`() = runTest {
        val source = object : MusicSource by MusicSources.current!! {
            override suspend fun recentlyPlayed(limit: Int) = super<MusicSource>.recentlyPlayed(limit)
            override suspend fun recentArtists(limit: Int) = super<MusicSource>.recentArtists(limit)
            override suspend fun browse(query: LibraryFilter.ItemsQuery, cachePrefix: String?): ItemsResponse =
                error("unavailable")
        }
        assertTrue(source.recentlyPlayed().isEmpty())
        assertTrue(source.recentArtists().isEmpty())

        var queried = false
        val unsupported = object : MusicSource by source {
            override val hasPlayHistory = false
            override suspend fun recentlyPlayed(limit: Int) = super<MusicSource>.recentlyPlayed(limit)
            override suspend fun recentArtists(limit: Int) = super<MusicSource>.recentArtists(limit)
            override suspend fun browse(query: LibraryFilter.ItemsQuery, cachePrefix: String?): ItemsResponse {
                queried = true
                return ItemsResponse()
            }
        }
        assertTrue(unsupported.recentlyPlayed().isEmpty())
        assertTrue(unsupported.recentArtists().isEmpty())
        assertTrue(!queried)
    }

    @Test
    fun `Jellyfin history respects caller limits and skips invalid limits or missing sessions`() = runTest {
        historyResponses((0 until 10).map { historyTrack("t$it", "a$it") })

        assertEquals(3, SourceQueries.recentlyPlayed(limit = 3).size)
        assertEquals(listOf("a0", "a1", "a2"), SourceQueries.recentArtists(limit = 3).map { it.id })
        assertEquals("3", requests.single { it.queryParameter("Ids") != null }.queryParameter("Limit"))
        requests.clear()

        assertTrue(SourceQueries.recentlyPlayed(limit = 0).isEmpty())
        assertTrue(SourceQueries.recentArtists(limit = -1).isEmpty())
        client.auth.clear()
        assertTrue(SourceQueries.recentlyPlayed().isEmpty())
        assertTrue(SourceQueries.recentArtists().isEmpty())
        assertTrue(requests.isEmpty())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun loadHome(cachedRecent: List<JellyfinItem>? = null): HomeUiState = runBlocking {
        val app = ApplicationProvider.getApplicationContext<AuraApp>()
        app.jellyfin.auth.save(client.auth.session!!)
        LibraryCache.clear()
        cachedRecent?.let { LibraryCache.put("home_recent:u", it) }
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val viewModel = HomeViewModel(app)
            withTimeout(10_000) { viewModel.state.first { it.fullyCached } }
        } finally {
            Dispatchers.resetMain()
            LibraryCache.clear()
            app.jellyfin.auth.clear()
        }
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
    fun `refreshAll invalidates cached bios lyrics and web hints`() = runTest {
        val source = MusicSources.current!!
        body = """{"Id":"bio","Name":"Artist","Overview":"Original bio"}"""
        assertEquals("Original bio", source.artistBio("bio"))
        body = """{"LyricText":"[00:01]Original lyrics"}"""
        assertEquals(listOf(TimedLyricLine(1000, "Original lyrics")), source.lyricsTimed("song"))
        assertEquals("Original bio", source.albumBio("bio"))
        source.lyricsTimed("song")
        assertEquals(2, requests.size)

        source.refreshAll()

        body = """{"Id":"bio","Name":"Artist","Overview":"Corrected bio"}"""
        assertEquals("Corrected bio", source.artistBio("bio"))
        body = """{"LyricText":"[00:02]Corrected lyrics"}"""
        assertEquals(listOf(TimedLyricLine(2000, "Corrected lyrics")), source.lyricsTimed("song"))
        assertEquals(4, requests.size)

        Prefs.lyricsWebFallback = true
        body = """{"Id":"web","Name":"Track"}"""
        val webSource = JellyfinSource(client, MediaRepository(client), fetchLyrics = { emptyList() }, lookupLyrics = {
            LyricsStore.parseLrcText("[00:03]Web lyrics")
        })
        webSource.lyricsTimed("web")
        assertEquals(true, webSource.cachedWebLyricsFound("web"))
        webSource.refreshAll()
        assertNull(webSource.cachedWebLyricsFound("web"))
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
    fun `jellyfin distinguishes explicit zero and single timed lines from plain lyrics`() = runTest {
        val source = MusicSources.current!!
        body = """{"Metadata":{},"Lyrics":[{"Text":"One line","Start":50000000,"Cues":[]}]}"""
        assertEquals(listOf(TimedLyricLine(5000, "One line")), source.lyricsTimed("single"))
        body = """{"Metadata":{},"Lyrics":[{"Text":"At the start","Start":0,"Cues":[]}]}"""
        assertEquals(listOf(TimedLyricLine(0, "At the start")), source.lyricsTimed("zero"))
        body = """{"Metadata":{},"Lyrics":[{"Text":"Plain line"}]}"""
        assertNull(source.lyricsTimed("plain-structured"))
        assertEquals(listOf(LyricLine(0, "Plain line", isSynced = false)),
            LyricsStore.fetchPreferred(client.api, "plain-structured"))
    }

    @Test
    fun `jellyfin repeats raw inline word tags unchanged across line stamps`() = runTest {
        body = """{"Metadata":{},"Lyrics":[{"Text":"v1: <00:06.00>One <00:07.00>two","Start":60000000,"Cues":[]},{"Text":"v1: <00:06.00>One <00:07.00>two","Start":100000000,"Cues":[]}]}"""
        val lines = MusicSources.current!!.lyricsTimed("multi")!!
        assertEquals(listOf(6000L, 10000L), lines.map { it.startMs })
        assertEquals(listOf(TimedWord(6000, "One"), TimedWord(7000, "two")), lines[0].words)
        assertEquals(listOf(TimedWord(10000, "One"), TimedWord(11000, "two")), lines[1].words)
        assertTrue(lines.all { it.text == "One two" && it.voice == LyricVoice("v1", LyricVoice.Kind.PRIMARY) })
        assertEquals(listOf(KaraokeSpan("One", true), KaraokeSpan(" two", false)), karaokeSpans(lines[1], 10500))
    }

    @Test
    fun `jellyfin cues and inline tags reach timed words`() = runTest {
        body = """{"Lyrics":[{"Text":"Hello world","Start":120000000,"Cues":[{"Position":0,"EndPosition":5,"Start":120000000,"End":125000000},{"Position":6,"EndPosition":11,"Start":125000000,"End":130000000},{"Position":-1,"EndPosition":999,"Start":0}]}]}"""
        val line = MusicSources.current!!.lyricsTimed("cues")!!.single()
        assertEquals("Hello world", line.text)
        assertEquals(listOf(TimedWord(12000, "Hello"), TimedWord(12500, "world")), line.words)
        body = """{"Lyrics":[{"Text":"<00:12>Hello <00:12.50>world<00:13>","Start":120000000}]}"""
        assertEquals(line, MusicSources.current!!.lyricsTimed("inline")!!.single())
    }

    @Test
    fun `jellyfin structured voices reach timed lines without leaking into words`() = runTest {
        body = """{"Lyrics":[{"Text":"v2: <00:12>Hello <00:12.50>world<00:13>","Start":120000000}]}"""
        val line = MusicSources.current!!.lyricsTimed("voice-inline")!!.single()
        assertEquals("Hello world", line.text)
        assertEquals(LyricVoice("v2", LyricVoice.Kind.SECONDARY), line.voice)
        assertEquals(listOf(TimedWord(12000, "Hello"), TimedWord(12500, "world")), line.words)
        body = """{"Lyrics":[{"Text":"v2: Hello world","Start":120000000,"Cues":[{"Position":0,"EndPosition":9,"Start":120000000},{"Position":10,"EndPosition":15,"Start":125000000}]}]}"""
        assertEquals(line, MusicSources.current!!.lyricsTimed("voice-cues")!!.single())
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
