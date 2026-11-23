package com.hans.aura.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LrclibTest {
    @Test
    fun `instrumental response is distinct from missing lyrics`() {
        val result = Lrclib.parseResponse(
            """{"id":42,"instrumental":true,"plainLyrics":null,"syncedLyrics":null}"""
        )
        assertEquals(LyricsResult.Instrumental, result)
    }

    @Test
    fun `synced and plain responses retain their lines`() {
        val synced = Lrclib.parseResponse("""{"syncedLyrics":"[00:01]Hello"}""")
        assertEquals(listOf(LyricLine(1000, "Hello", isSynced = true)), synced.lines)
        val plain = Lrclib.parseResponse("""{"plainLyrics":"Hello"}""")
        assertEquals("Hello", plain.lines.single().text)
        assertTrue(plain.lines.none { it.isSynced })
    }

    @Test
    fun `empty response is not instrumental`() {
        assertEquals(LyricsResult.NotFound, Lrclib.parseResponse(
            """{"instrumental":false,"plainLyrics":null,"syncedLyrics":null}"""
        ))
    }

    @Test
    fun `instrumental result is cached and line-only consumers do not re-fetch`() = runTest {
        val item = JellyfinItem(id = "instrumental-cache", name = "", type = "Audio").toDomainItem() as com.hans.aura.model.Track
        val result = Lrclib.cachedLookup(item) {
            Lrclib.parseResponse("""{"instrumental":true,"plainLyrics":null,"syncedLyrics":null}""")
        }
        assertEquals(LyricsResult.Instrumental, result)
        assertEquals(result, Lrclib.cached(item.id))
        assertEquals(result, Lrclib.cachedLookup(item) { error("must not re-fetch") })
        assertEquals(result, Lrclib.lookupResult(item))
        assertTrue(Lrclib.lookup(item).isEmpty())
    }

    @Test
    fun `malformed response is not cached and can recover`() = runTest {
        val item = JellyfinItem(id = "instrumental-retry", name = "", type = "Audio").toDomainItem() as com.hans.aura.model.Track
        assertEquals(LyricsResult.NotFound, Lrclib.cachedLookup(item) {
            Lrclib.parseResponse("not json")
        })
        assertEquals(null, Lrclib.cached(item.id))
        assertEquals(LyricsResult.Instrumental, Lrclib.cachedLookup(item) {
            Lrclib.parseResponse("""{"instrumental":true}""")
        })
    }
}
