package com.hans.aura.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.Retrofit

@RunWith(RobolectricTestRunner::class)
class CachePersistenceTest {
    private lateinit var db: CacheDatabase
    private lateinit var disk: CacheDisk
    private val items = listOf(Fixtures.item("a"), Fixtures.item("b"))

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Prefs.init(context)
        Prefs.hiddenLibraries = emptySet()
        db = Room.inMemoryDatabaseBuilder(context, CacheDatabase::class.java).build()
        disk = CacheDisk(db.cacheDao())
        coldStart()
    }

    private fun coldStart() {
        LibraryCache.install(disk)
        AtmosCatalog.install(disk)
        AtmosFilter.adopt(AtmosCatalog.Catalog())
    }

    @After
    fun tearDown() = runBlocking {
        disk.read { }
        LibraryCache.install(null)
        AtmosCatalog.install(null)
        Prefs.hiddenLibraries = emptySet()
        disk.close()
        db.close()
    }

    @Test
    fun `lists including empty success survive cold start`() = runBlocking {
        LibraryCache.put("home_new:u", items)
        LibraryCache.put("genres:u", emptyList())
        coldStart()
        assertEquals(items, LibraryCache.get("home_new:u"))
        assertEquals(emptyList<JellyfinItem>(), LibraryCache.get("genres:u"))
        assertEquals(items, LibraryCache.peek("home_new:u"))
        assertNull(LibraryCache.get("home_new:other-user"))
    }

    @Test
    fun `expired and corrupt lists are misses and deleted`() = runBlocking {
        disk.read {
            putList(CachedLibraryList("expired", 0, jellyfinJson.encodeToString(items)))
            putList(CachedLibraryList("corrupt", System.currentTimeMillis(), "not json"))
        }
        assertNull(LibraryCache.get("expired"))
        assertNull(LibraryCache.get("corrupt"))
        disk.read {
            assertNull(list("expired"))
            assertNull(list("corrupt"))
        }
    }

    @Test
    fun `eviction predicates see suffixed disk keys and clear removes all rows`() = runBlocking {
        Prefs.hiddenLibraries = setOf("lib-atmos")
        val suffix = LibraryFilter.cacheSuffix()
        LibraryCache.put("Tracks:u", items)
        LibraryCache.put("Albums:u", items)
        coldStart()
        LibraryCache.evictWhere { it == "Tracks:u$suffix" }
        assertNull(LibraryCache.get("Tracks:u"))
        assertEquals(items, LibraryCache.get("Albums:u"))
        LibraryCache.evict("Albums:u")
        coldStart()
        assertNull(LibraryCache.get("Albums:u"))
        LibraryCache.put("remaining:u", items)
        LibraryCache.clear()
        coldStart()
        assertNull(LibraryCache.get("remaining:u"))
        assertTrue(disk.read { listKeys().isEmpty() })
    }

    private fun api(server: MockWebServer): JellyfinApi = Retrofit.Builder()
        .baseUrl(server.url("/"))
        .addConverterFactory(jellyfinJson.asConverterFactory("application/json".toMediaType()))
        .build().create(JellyfinApi::class.java)

    @Test
    fun `cold Atmos restore adopts ids without any Items requests`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"Items":[{"Id":"t","AlbumId":"a","MediaSources":[{"MediaStreams":[{"Codec":"eac3","Profile":"JOC"}]}]}],"TotalRecordCount":1}"""))
            val api = api(server)
            val scanned = AtmosCatalog.catalog(api, "u")
            assertEquals(setOf("a"), scanned.albumIds)
            assertEquals(setOf("t"), scanned.trackIds)
            assertEquals("/Items", server.takeRequest().requestUrl!!.encodedPath)
            assertEquals(1, server.requestCount)
            coldStart()
            assertEquals(scanned, AtmosCatalog.catalog(api, "u"))
            assertEquals(scanned.albumIds, AtmosFilter.albumIds())
            assertEquals(scanned.trackIds, AtmosFilter.trackIds())
            assertEquals(1, server.requestCount)
            AtmosCatalog.clear()
            coldStart()
            assertNull(disk.read { atmos("u") })
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `expired corrupt and different user Atmos rows require a scan`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            disk.read {
                putAtmos(CachedAtmosCatalog("expired", 0, "[]", "[]"))
                putAtmos(CachedAtmosCatalog("corrupt", System.currentTimeMillis(), "broken", "[]"))
                putAtmos(CachedAtmosCatalog("other-user", System.currentTimeMillis(), "[\"private\"]", "[]"))
            }
            for (user in listOf("expired", "corrupt", "new-user")) {
                server.enqueue(MockResponse().setBody("""{"Items":[],"TotalRecordCount":0}"""))
                assertEquals(AtmosCatalog.Catalog(), AtmosCatalog.catalog(api(server), user))
                assertEquals("/Items", server.takeRequest().requestUrl!!.encodedPath)
            }
            assertEquals(3, server.requestCount)
            coldStart()
            assertEquals(AtmosCatalog.Catalog(), AtmosCatalog.catalog(api(server), "expired"))
            assertEquals(3, server.requestCount)
        } finally {
            server.shutdown()
        }
    }
}
