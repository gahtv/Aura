package com.hans.aura.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class ProviderLyricsTest {
    private val searchPath = "/Audio/song/RemoteSearch/Lyrics"
    private val savePath = "$searchPath/LrcLib:42="
    private val latestLyrics = """{"Lyrics":[{"Start":12340000,"Text":"Saved line"}]}"""
    private val server = MockWebServer()
    private lateinit var client: JellyfinClient
    private lateinit var source: JellyfinSource

    @Before
    fun startServer() {
        server.start()
        client = JellyfinClient(ApplicationProvider.getApplicationContext<Context>())
        client.auth.save(Session(server.url("/").toString(), "user", "test", "token", "device"))
        source = JellyfinSource(client, MediaRepository(client, { client.auth.session }))
    }

    @After
    fun stopServer() {
        Prefs.lyricsWebFallback = false
        MusicSources.reset()
        server.shutdown()
    }

    @Test
    fun `absent lyrics are cached across pane calls and reentry`() = runTest {
        assertAbsenceCached(404)
    }

    @Test
    fun `successful response without lines is cached`() = runTest {
        assertAbsenceCached(200)
    }

    private suspend fun assertAbsenceCached(status: Int) {
        Prefs.lyricsWebFallback = false
        MusicSources.install(source)
        repeat(4) { reply(status) }
        repeat(2) {
            assertEquals(null, source.lyricsTimed("song"))
            assertEquals(emptyList<LyricLine>(), LyricsStore.fetchPreferred(
                client.api, "song"
            ))
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `server and authorization errors are retried`() = runTest {
        for (status in listOf(500, 401, 403)) {
            reply(status)
            reply(200, latestLyrics)
            assertEquals(emptyList<LyricLine>(), source.jellyfinLyrics("song$status"))
            assertEquals("Saved line", source.jellyfinLyrics("song$status").single().text)
        }
        assertEquals(6, server.requestCount)
    }

    @Test
    fun `network errors are not cacheable and injected failures retry`() = runTest {
        val api = object : JellyfinApi by FakeJellyfinApi() {
            override suspend fun getLyrics(itemId: String): LyricsResponse = throw IOException("offline")
        }
        var calls = 0
        val retrying = JellyfinSource(client, MediaRepository(client, { client.auth.session }),
            fetchLyrics = {
                calls++
                LyricsStore.fetchCacheable(api, it)
            })
        repeat(2) { assertEquals(emptyList<LyricLine>(), retrying.jellyfinLyrics("song")) }
        assertEquals(2, calls)
    }

    @Test
    fun `saving lyrics replaces cached absence`() = runTest {
        reply(404)
        assertEquals(emptyList<LyricLine>(), source.jellyfinLyrics("song"))
        reply(200, latestLyrics)
        assertEquals(ProviderLyricsResult.Success(Unit), source.saveProviderLyrics("song", "LrcLib:42="))
        assertEquals("Saved line", source.jellyfinLyrics("song").single().text)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `cached absence still allows web fallback`() = runTest {
        Prefs.init(ApplicationProvider.getApplicationContext<Context>())
        Prefs.lyricsWebFallback = true
        var lookups = 0
        val fallback = JellyfinSource(client, MediaRepository(client, { client.auth.session }),
            lookupLyrics = {
                lookups++
                listOf(LyricLine(1234L, "Web line", true))
            })
        reply(404)
        repeat(2) { reply(200, """{"Id":"song","Name":"Song"}""") }
        repeat(2) {
            assertEquals("Web line", fallback.lyricsTimed("song")?.single()?.text)
            assertEquals(true, fallback.cachedWebLyricsFound("song"))
        }
        assertEquals(2, lookups)
        assertRequest("GET", "/Audio/song/lyrics")
        repeat(2) { assertRequest("GET", "/Users/user/Items/song") }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `search previews candidates without saving and selection replaces cached lyrics`() = runTest {
        reply(200, """{"Lyrics":[{"Text":"Existing line"}]}""")
        reply(200, """
            [
              {"Id":"LrcLib:42=","ProviderName":"LrcLib","Lyrics":$latestLyrics},
              {"Id":"plain","ProviderName":"Other provider","Lyrics":{"Lyrics":[{"Text":"Plain line"}]}}
            ]
        """.trimIndent())

        assertEquals("Existing line", source.jellyfinLyrics("song").single().text)
        assertRequest("GET", "/Audio/song/lyrics")
        assertEquals(
            ProviderLyricsResult.Success(
                listOf(
                    ProviderLyricMatch("LrcLib:42=", "LrcLib", true, listOf("Saved line")),
                    ProviderLyricMatch("plain", "Other provider", false, listOf("Plain line"))
                )
            ),
            source.searchProviderLyrics("song")
        )
        assertRequest("GET", searchPath)
        assertEquals(2, server.requestCount)

        reply(200, latestLyrics)
        assertEquals(ProviderLyricsResult.Success(Unit), source.saveProviderLyrics("song", "LrcLib:42="))
        assertEquals(LyricLine(1234L, "Saved line", true), source.jellyfinLyrics("song").single())
        assertRequest("POST", savePath)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `empty results forbidden accounts and missing endpoints are distinct`() = runTest {
        reply(200, "[]")
        assertEquals(ProviderLyricsResult.Success(emptyList<ProviderLyricMatch>()), source.searchProviderLyrics("song"))
        reply(403)
        assertEquals(ProviderLyricsResult.Forbidden, source.searchProviderLyrics("song"))
        reply(404)
        assertEquals(ProviderLyricsResult.Unavailable, source.searchProviderLyrics("song"))
        reply(500)
        assertEquals(ProviderLyricsResult.Failed, source.searchProviderLyrics("song"))
    }

    @Test
    fun `save rejection leaves cached lyrics alone`() = runTest {
        reply(200, """{"Lyrics":[{"Text":"Existing line"}]}""")
        reply(403)

        val previous = source.jellyfinLyrics("song")
        assertEquals(ProviderLyricsResult.Forbidden, source.saveProviderLyrics("song", "LrcLib:42="))
        assertEquals(previous, source.jellyfinLyrics("song"))
        assertRequest("GET", "/Audio/song/lyrics")
        assertRequest("POST", savePath)
        assertEquals(2, server.requestCount)
    }

    private fun reply(status: Int, body: String = "{}") {
        server.enqueue(MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body))
    }

    private fun assertRequest(method: String, path: String) {
        val request = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        assertEquals(method, request.method)
        assertEquals(path, request.path)
    }
}
