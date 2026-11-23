package com.hans.aura.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hans.aura.model.Track
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class InstantMixTest {
    private lateinit var server: MockWebServer
    private lateinit var client: JellyfinClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = JellyfinClient(ApplicationProvider.getApplicationContext<Context>())
        client.auth.save(Session(server.url("/").toString(), "user", "test", "token", "device"))
    }

    @After
    fun tearDown() {
        client.auth.clear()
        server.shutdown()
    }

    @Test
    fun `typeless instant mix rows remain playable tracks`() = runBlocking {
        server.enqueue(MockResponse().setBody("""
            {"Items":[
              {"Id":"typeless","Name":"Typeless"},
              {"Id":"audio","Type":"Audio","Name":"Audio"},
              {"Id":"video","Type":"Video","Name":"Video"}
            ],"TotalRecordCount":3}
        """.trimIndent()))

        val tracks = MediaRepository(client).trackMix(Track(id = "seed", name = "Seed"), 10)

        assertEquals(listOf("typeless", "audio"), tracks.map { it.id })
        val request = server.takeRequest(5, TimeUnit.SECONDS)
        assertTrue(request?.path.orEmpty().startsWith("/Items/seed/InstantMix"))
    }
}
