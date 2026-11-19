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
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class ProviderLyricsTest {
    private val searchPath = "/Audio/song/RemoteSearch/Lyrics"
    private val savePath = "$searchPath/LrcLib:42="
    private val latestLyrics = """{"Lyrics":[{"Start":12340000,"Text":"Saved line"}]}"""
    private val server = MockWebServer()
    private lateinit var source: JellyfinSource

    @Before
    fun startServer() {
        server.start()
        val client = JellyfinClient(ApplicationProvider.getApplicationContext<Context>())
        client.auth.save(Session(server.url("/").toString(), "user", "test", "token", "device"))
        source = JellyfinSource(client, MediaRepository(client, { client.auth.session }))
    }

    @After
    fun stopServer() = server.shutdown()

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
