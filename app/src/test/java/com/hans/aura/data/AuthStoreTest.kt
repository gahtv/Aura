package com.hans.aura.data

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Auth header formatting, direct-play stream URLs and session persistence. */
@RunWith(RobolectricTestRunner::class)
class AuthStoreTest {

    private lateinit var store: AuthStore

    private val session = Session(
        serverUrl = "http://192.168.2.166:8096",
        userId = "user-1",
        userName = "hans",
        accessToken = "tok-123",
        deviceId = "device-1"
    )

    @Before
    fun setUp() {
        store = AuthStore(ApplicationProvider.getApplicationContext())
        store.clear()
    }

    // ---- auth header -------------------------------------------------------

    @Test
    fun `auth header carries no token before sign-in`() {
        val header = AuthStore.authHeader(null, deviceId = "device-1", appName = "Aura", version = "0.4.0")

        assertTrue(header.startsWith("MediaBrowser "))
        assertTrue(header.contains("Client=\"Aura\""))
        assertTrue(header.contains("DeviceId=\"device-1\""))
        assertTrue(header.contains("Version=\"0.4.0\""))
        assertFalse("no session means no token", header.contains("Token="))
    }

    @Test
    fun `auth header appends the token when signed in`() {
        val header = AuthStore.authHeader(session, deviceId = "device-1", appName = "Aura", version = "0.4.0")

        assertTrue(header.endsWith(", Token=\"tok-123\""))
    }

    @Test
    fun `auth header never uses the legacy X-Emby scheme`() {
        val header = AuthStore.authHeader(session, "device-1", "Aura", "0.4.0")
        assertTrue(header.startsWith("MediaBrowser Client="))
    }

    // ---- stream urls -------------------------------------------------------

    @Test
    fun `audio stream url keeps the original container static`() {
        assertEquals(
            "http://h:8096/Audio/item-1/stream.flac?static=true",
            AuthStore.audioStreamUrl("http://h:8096", "item-1", "flac")
        )
    }

    @Test
    fun `audio stream url falls back to mp4 for a missing or blank container`() {
        assertEquals(
            "http://h:8096/Audio/item-1/stream.mp4?static=true",
            AuthStore.audioStreamUrl("http://h:8096", "item-1", null)
        )
        assertEquals(
            "http://h:8096/Audio/item-1/stream.mp4?static=true",
            AuthStore.audioStreamUrl("http://h:8096", "item-1", "  ")
        )
    }

    @Test
    fun `image url carries the tag when present and maxWidth always`() {
        assertEquals(
            "http://h:8096/Items/item-1/Images/Primary?maxWidth=600&tag=abc",
            AuthStore.imageUrl("http://h:8096", "item-1", "abc")
        )
        assertEquals(
            "http://h:8096/Items/item-1/Images/Primary?maxWidth=300",
            AuthStore.imageUrl("http://h:8096", "item-1", null, maxWidth = 300)
        )
    }

    // ---- host --------------------------------------------------------------

    @Test
    fun `host strips the scheme and the trailing slash`() {
        assertEquals("192.168.2.10:8096", AuthStore.host("http://192.168.2.10:8096/"))
        assertEquals("jellyfin.example.com", AuthStore.host("https://jellyfin.example.com"))
        assertEquals("<none>", AuthStore.host(null))
    }

    // ---- session -----------------------------------------------------------

    @Test
    fun `session round trips through a fresh store instance`() {
        store.save(session)

        assertTrue(store.isSignedIn)
        assertEquals(session, store.session)
        assertEquals("http://192.168.2.166:8096", store.lastServerUrl)

        val reopened = AuthStore(ApplicationProvider.getApplicationContext())
        assertEquals(session, reopened.session)
    }

    @Test
    fun `signing out keeps the server address but drops the token`() {
        store.save(session)

        store.clearSession()

        assertNull(store.session)
        assertEquals("http://192.168.2.166:8096", store.lastServerUrl)
    }

    @Test
    fun `changing server drops the address too`() {
        store.save(session)

        store.clearServer()

        assertNull(store.session)
        assertNull("the login screen must start with an empty URL", store.lastServerUrl)
    }

    @Test
    fun `device id is stable across calls`() {
        val first = AuthStore.newDeviceId(ApplicationProvider.getApplicationContext())
        val second = AuthStore.newDeviceId(ApplicationProvider.getApplicationContext())
        assertEquals(first, second)
        assertTrue(first.isNotBlank())
    }
}
