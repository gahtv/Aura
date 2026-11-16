package com.hans.aura.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The one thing the player depends on from a [MusicSource]: the playable target
 * for an item, with the token of the session in effect right now.
 */
@RunWith(RobolectricTestRunner::class)
class MusicSourceTest {

    private val session = Session(
        serverUrl = "http://192.168.2.166:8096",
        userId = "user-1",
        userName = "hans",
        accessToken = "tok-123",
        deviceId = "device-1"
    )

    private lateinit var client: JellyfinClient
    private lateinit var source: JellyfinSource

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        client = JellyfinClient(context)
        client.auth.clear()
        source = JellyfinSource(client, MediaRepository(client, { client.auth.session }))
    }

    @Test
    fun `stream target is the direct-play url with the session token`() = runTest {
        client.auth.save(session)

        val target = source.streamTarget("item-1", container = "flac")

        assertEquals(
            "http://192.168.2.166:8096/Audio/item-1/stream.flac?static=true",
            target.url
        )
        val header = target.headers["Authorization"]
        assertTrue("header missing", header != null)
        assertTrue(header!!.startsWith("MediaBrowser Client="))
        assertTrue(header.contains("Client=\"$APP_NAME\""))
        assertTrue(header.endsWith(", Token=\"tok-123\""))
    }

    @Test
    fun `stream target falls back to mp4 and sends no token when signed out`() = runTest {
        client.auth.clear()

        val target = source.streamTarget("item-1")

        // No session means no host to prefix: same shape the player produced
        // before, and the header carries the client identity without a token.
        assertEquals("/Audio/item-1/stream.mp4?static=true", target.url)
        assertFalse(target.headers.getValue("Authorization").contains("Token="))
    }

    @Test
    fun `stream uri honours an explicit host and container`() {
        client.auth.save(session)

        assertEquals(
            "https://other:8920/Audio/item-2/stream.m4a?static=true",
            source.streamUri("item-2", container = "m4a", serverUrl = "https://other:8920")
        )
    }
}
