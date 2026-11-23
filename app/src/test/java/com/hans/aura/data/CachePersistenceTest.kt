package com.hans.aura.data

import android.content.Context
import android.database.sqlite.SQLiteBlobTooBigException
import android.database.sqlite.SQLiteException
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.hans.aura.playback.PlaybackReporter
import java.lang.reflect.Proxy
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode
import retrofit2.Retrofit

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
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
        Favorites.clear()
        PagedLibrary.clear()
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
    fun `database read failures delete bad rows and Atmos falls back to a scan`() = runBlocking {
        disk.read {
            putList(CachedLibraryList("bad", System.currentTimeMillis(), "[]"))
            putAtmos(CachedAtmosCatalog("u", System.currentTimeMillis(), "[]", "[]"))
        }
        disk.close()
        val delegate = db.cacheDao()
        var deletedAtmos = false
        disk = CacheDisk(object : CacheDao by delegate {
            override fun list(key: String): CachedLibraryList? = throw SQLiteBlobTooBigException("oversized row")
            override fun atmos(key: String): CachedAtmosCatalog? = throw SQLiteException("read failed")
            override fun deleteAtmos(key: String) {
                deletedAtmos = true
                delegate.deleteAtmos(key)
            }
            override fun putList(row: CachedLibraryList) = throw SQLiteException("write failed")
        })
        coldStart()
        assertNull(LibraryCache.get("bad"))
        assertEquals(emptyList<String>(), disk.read { listKeys() })
        LibraryCache.put("write-fails", items)
        assertEquals(emptyList<String>(), disk.read { listKeys() })
        assertEquals(items, LibraryCache.peek("write-fails"))
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"Items":[],"TotalRecordCount":0}"""))
            assertEquals(AtmosCatalog.Catalog(), AtmosCatalog.catalog(api(server), "u"))
            assertEquals(1, server.requestCount)
            assertTrue(deletedAtmos)
        }
    }

    @Test
    fun `cleanup failure is still a cache miss and closed disk rejects writes safely`() = runBlocking {
        disk.read { }
        disk.close()
        val delegate = db.cacheDao()
        disk = CacheDisk(object : CacheDao by delegate {
            override fun list(key: String): CachedLibraryList? = throw SQLiteException("read failed")
            override fun deleteList(key: String) = throw SQLiteException("delete failed")
        })
        coldStart()
        assertNull(LibraryCache.get("bad"))
        disk.close()
        disk.write { error("closed executor must not run this") }
        disk = CacheDisk(delegate)
    }

    @Test
    fun `expired and corrupt lists are misses and deleted`() = runBlocking<Unit> {
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
        assertEquals(true, disk.read { listKeys().isEmpty() })
    }

    private fun api(server: MockWebServer): JellyfinApi = Retrofit.Builder()
        .baseUrl(server.url("/"))
        .addConverterFactory(jellyfinJson.asConverterFactory("application/json".toMediaType()))
        .build().create(JellyfinApi::class.java)

    @Test
    fun `favorite toggle cannot restore pre-mutation home album after cold start`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val album = Fixtures.item("a")
            LibraryCache.put("home_new:u", listOf(album))
            LibraryCache.put("arbitrary-selection-key", listOf(album))
            LibraryCache.put("home_recent:u", items)
            LibraryCache.put("lib_tracks:u:SortName:false", items)
            PagedLibrary.put("lib_albums:u:SortName:false", listOf(album), 1)
            PagedLibrary.put("lib_tracks:u:SortName:false", items, 2)
            server.enqueue(MockResponse().setBody("""{"IsFavorite":true}"""))
            assertEquals(true, Favorites.toggle(api(server), "u", album))
            assertTrue(Favorites.isFavorite(album))
            assertEquals(listOf(album), LibraryCache.peek("home_new:u"))
            assertEquals(listOf(album), LibraryCache.peek("arbitrary-selection-key"))
            assertEquals(listOf(album), PagedLibrary.get("lib_albums:u:SortName:false")?.items)
            assertNull(LibraryCache.peek("home_recent:u"))
            assertNull(LibraryCache.peek("lib_tracks:u:SortName:false"))
            assertNull(PagedLibrary.get("lib_tracks:u:SortName:false"))
            assertEquals(true, disk.read { listKeys().isEmpty() })
            coldStart()
            assertNull(LibraryCache.get("home_new:u"))
            assertNull(LibraryCache.get("arbitrary-selection-key"))
            assertFalse(Favorites.isFavorite(album))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `rejected favorite preserves persisted lists after cold start`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            LibraryCache.put("home_new:u", items)
            server.enqueue(MockResponse().setResponseCode(403))
            assertNull(Favorites.toggle(api(server), "u", items.first()))
            coldStart()
            assertEquals(items, LibraryCache.get("home_new:u"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `playback history invalidation drops only history disk rows`() = runBlocking {
        val history = listOf("home_recent:u", "home_recent_artists:u", "artist_top:a",
            "lib_tracks:u:PlayCount:true", "lib_atmos_audio:u:DatePlayed:false:all",
            "atmos_audio:u:PlayCount:true:all", "lib_albums:u:DatePlayed,SortName:true")
        val retained = listOf("home_new:u", "home_artists:u", "artist_albums:a",
            "lib_albums:u:SortName:false", "lib_tracks:u:DateCreated:true", "col:playlist")
        for (hidden in listOf(emptySet(), setOf("hidden"))) {
            Prefs.hiddenLibraries = hidden
            (history + retained).forEach { LibraryCache.put(it, items) }
        }
        PagedLibrary.put("lib_tracks:u:PlayCount:true", items, 2)
        LibraryCache.evictPlaybackHistoryFromDisk()
        (history + retained).forEach { assertEquals(items, LibraryCache.peek(it)) }
        assertEquals(items, PagedLibrary.get("lib_tracks:u:PlayCount:true")?.items)
        coldStart()
        for (hidden in listOf(emptySet(), setOf("hidden"))) {
            Prefs.hiddenLibraries = hidden
            history.forEach { assertNull(it, LibraryCache.get(it)) }
            retained.forEach { assertEquals(it, items, LibraryCache.get(it)) }
        }
    }

    @Test
    fun `playback invalidates persisted item data in every list but preserves session memory`() = runBlocking {
        val played = Fixtures.item("played", playCount = 0)
        val other = Fixtures.item("other", playCount = 3)
        for (hidden in listOf(emptySet(), setOf("hidden"))) {
            Prefs.hiddenLibraries = hidden
            LibraryCache.put("col:album", listOf(played, other))
            LibraryCache.put("lib_tracks:u:SortName:false", listOf(played))
            LibraryCache.put("col:other", listOf(other))
            LibraryCache.put("empty", emptyList())
        }
        PagedLibrary.put("tracks", listOf(played), 1)
        retrofit2.Response.error<Unit>(503, okhttp3.ResponseBody.create(null, "failed"))
            .invalidateHistoryOnSuccess(played.id)
        assertEquals(8, disk.read { listKeys().size })
        retrofit2.Response.success(Unit).invalidateHistoryOnSuccess(played.id)
        assertEquals(listOf(played, other), LibraryCache.peek("col:album"))
        assertEquals(listOf(played), PagedLibrary.get("tracks")?.items)
        coldStart()
        for (hidden in listOf(emptySet(), setOf("hidden"))) {
            Prefs.hiddenLibraries = hidden
            assertNull(LibraryCache.get("col:album"))
            assertNull(LibraryCache.get("lib_tracks:u:SortName:false"))
            assertEquals(listOf(other), LibraryCache.get("col:other"))
            assertEquals(emptyList<JellyfinItem>(), LibraryCache.get("empty"))
        }
    }

    @Test
    fun `progress report preserves memory and disk while stop evicts only disk history`() = runBlocking {
        MockWebServer().use { server ->
            val client = JellyfinClient(ApplicationProvider.getApplicationContext())
            client.auth.save(Session(server.url("/").toString(), "u", "user", "token", client.deviceId))
            var state = Player.STATE_READY
            val item = MediaItem.Builder().setMediaId("a").build()
            val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
                when (method.name) {
                    "getCurrentMediaItem", "getMediaItemAt" -> item
                    "getPlaybackState" -> state
                    "isPlaying", "getPlayWhenReady" -> state == Player.STATE_READY
                    "getCurrentPosition" -> 10_000L
                    "getMediaItemCount" -> 1
                    "getRepeatMode" -> Player.REPEAT_MODE_OFF
                    "getShuffleModeEnabled" -> false
                    else -> error("Unexpected player call: ${method.name}")
                }
            } as Player
            val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
            val reporter = PlaybackReporter(client, player, scope = scope)
            suspend fun awaitReport(path: String) {
                assertEquals(path, server.takeRequest(5, TimeUnit.SECONDS)?.path)
                scope.coroutineContext.job.children.toList().forEach { it.join() }
            }
            try {
                server.enqueue(MockResponse().setResponseCode(204))
                reporter.start()
                shadowOf(Looper.getMainLooper()).idle()
                awaitReport("/Sessions/Playing")
                LibraryCache.put("home_recent:u", items)
                val unrelated = listOf(Fixtures.item("unrelated"))
                LibraryCache.put("home_new:u", unrelated)
                PagedLibrary.put("lib_tracks:u:PlayCount:true", items, 2)
                val before = disk.read { listKeys().associateWith { list(it) } }
                server.enqueue(MockResponse().setResponseCode(204))
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))
                awaitReport("/Sessions/Playing/Progress")
                assertEquals(items, LibraryCache.peek("home_recent:u"))
                assertEquals(unrelated, LibraryCache.peek("home_new:u"))
                assertEquals(before, disk.read { listKeys().associateWith { list(it) } })
                state = Player.STATE_IDLE
                server.enqueue(MockResponse().setResponseCode(204))
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
                awaitReport("/Sessions/Playing/Stopped")
                assertEquals(items, LibraryCache.peek("home_recent:u"))
                assertEquals(items, PagedLibrary.get("lib_tracks:u:PlayCount:true")?.items)
                coldStart()
                assertNull(LibraryCache.get("home_recent:u"))
                assertEquals(unrelated, LibraryCache.get("home_new:u"))
            } finally {
                reporter.stop()
                client.auth.clearSession()
            }
        }
    }

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
