package com.hans.aura.ui.library

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.hans.aura.AuraApp
import com.hans.aura.data.AtmosFilter
import com.hans.aura.data.Favorites
import com.hans.aura.data.ItemsResponse
import com.hans.aura.data.JellyfinItem
import com.hans.aura.data.LibraryCache
import com.hans.aura.data.LibraryFilter
import com.hans.aura.data.MusicSource
import com.hans.aura.data.MusicSources
import com.hans.aura.data.PagedLibrary
import com.hans.aura.data.Session
import com.hans.aura.data.toDomainItem
import com.hans.aura.data.toDomainItems
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LibraryViewModelTest {
    private val store = ViewModelStore()
    private val requests = Channel<CompletableDeferred<ItemsResponse>>(Channel.UNLIMITED)
    private val queries = Channel<LibraryFilter.ItemsQuery>(Channel.UNLIMITED)
    private lateinit var app: AuraApp

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        app = ApplicationProvider.getApplicationContext()
        AtmosFilter.setEnabled(false)
        LibraryCache.clear()
        PagedLibrary.clear()
        val source = MusicSources.current!!
        MusicSources.install(object : MusicSource by source {
            override val id = "test"
            override suspend fun atmosAlbumIds(): Set<String> = emptySet()
            override suspend fun browse(query: LibraryFilter.ItemsQuery, cachePrefix: String?): ItemsResponse {
                val response = CompletableDeferred<ItemsResponse>()
                queries.send(query)
                requests.send(response)
                return if (query.includeItemTypes == "Playlist") response.await()
                else withContext(NonCancellable) { response.await() }
            }
        })
    }

    @After
    fun tearDown() {
        store.clear()
        app.jellyfin.auth.clear()
        MusicSources.reset()
        LibraryCache.clear()
        PagedLibrary.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `overlapping restored page is replaced and pagination finishes without duplicate ids`() = runBlocking {
        val first = model()
        val cached = (0 until 60).map { JellyfinItem(id = "$it", type = "MusicAlbum") }
        withTimeout(5000) { queries.receive() }
        withTimeout(5000) { requests.receive() }.complete(ItemsResponse(items = cached, totalRecordCount = 120))
        withTimeout(5000) { first.state.first { !it.loading && it.items.isNotEmpty() } }
        val key = first.state.value.cacheKey
        store.clear()
        PagedLibrary.clear()
        val restored = model()
        withTimeout(5000) { restored.state.first { it.items == cached.toDomainItems() } }
        val live = listOf(JellyfinItem(id = "inserted", type = "MusicAlbum")) + (0 until 120).map { JellyfinItem(id = "$it", type = "MusicAlbum") }
        repeat(2) {
            restored.loadNextPage()
            val query = withTimeout(5000) { queries.receive() }
            withTimeout(5000) { requests.receive() }.complete(ItemsResponse(
                items = live.drop(query.startIndex).take(query.limit), totalRecordCount = live.size
            ))
            withTimeout(5000) { restored.state.first { !it.loading } }
        }
        assertEquals(live.map { it.id }, restored.state.value.items.map { it.id })
        assertEquals(restored.state.value.total, restored.state.value.items.size)
        assertFalse(restored.state.value.loading)
        assertEquals(live, PagedLibrary.get(key)?.items)
    }

    @Test
    fun `changing sort during load starts a new request and ignores stale response`() = runBlocking {
        val model = model()
        val first = withTimeout(5000) { requests.receive() }
        val firstJob = model.viewModelScope.coroutineContext.job.children.last()
        assertTrue(model.state.value.loading)
        val nextSort = if (model.state.value.sort == SortMode.TITLE) SortMode.DATE_ADDED else SortMode.TITLE
        model.onSortClick(nextSort)
        val second = try {
            withTimeout(5000) { requests.receive() }
        } catch (e: Exception) {
            first.complete(ItemsResponse())
            throw e
        }
        second.complete(ItemsResponse(items = listOf(JellyfinItem(id = "new", type = "MusicAlbum")), totalRecordCount = 1))
        withTimeout(5000) { model.state.first { it.items.isNotEmpty() && !it.loading } }
        assertEquals(listOf("new"), model.state.value.items.map { it.id })
        first.complete(ItemsResponse(items = listOf(JellyfinItem(id = "stale", type = "MusicAlbum")), totalRecordCount = 1))
        // Wait for the cancelled request's coroutine to finish, not a timing delay.
        withTimeout(5000) { firstJob.join() }
        assertEquals(listOf("new"), model.state.value.items.map { it.id })
        assertFalse(model.state.value.loading)
        assertNull(model.state.value.error)
    }

    @Test
    fun `retry with same key ignores stale failure while current request is loading`() = runBlocking {
        val model = model()
        val first = withTimeout(5000) { requests.receive() }
        val firstJob = model.viewModelScope.coroutineContext.job.children.last()
        model.retry()
        val second = withTimeout(5000) { requests.receive() }
        first.completeExceptionally(IllegalStateException("stale failure"))
        withTimeout(5000) { firstJob.join() }
        assertTrue(model.state.value.loading)
        assertNull(model.state.value.error)
        second.complete(ItemsResponse())
        withTimeout(5000) { model.state.first { !it.loading } }
        assertTrue(model.state.value.items.isEmpty())
        assertEquals(0, model.state.value.total)
        assertNull(model.state.value.error)
    }

    @Test
    fun `current failure clears loading and retry can show albums`() = runBlocking {
        val model = model()
        withTimeout(5000) { requests.receive() }.completeExceptionally(IllegalStateException("offline"))
        withTimeout(5000) { model.state.first { !it.loading && it.error != null } }
        assertEquals("offline", model.state.value.error)
        model.retry()
        withTimeout(5000) { requests.receive() }.complete(
            ItemsResponse(items = listOf(JellyfinItem(id = "recovered", type = "MusicAlbum")), totalRecordCount = 1)
        )
        withTimeout(5000) { model.state.first { !it.loading } }
        assertEquals(listOf("recovered"), model.state.value.items.map { it.id })
        assertNull(model.state.value.error)
    }

    @Test
    fun `creating a playlist refreshes a previously loaded Playlists tab`() = runBlocking {
        MockWebServer().use { server ->
            val userId = server.signIn()
            val existing = JellyfinItem(id = "existing", type = "Playlist")
            val model = loadPlaylists(existing)
            val activeKey = model.state.value.cacheKey
            assertEquals(listOf(existing), LibraryCache.peek(activeKey))
            assertEquals(listOf(existing), PagedLibrary.get(activeKey)?.items)
            val otherSortKey = "lib_playlists:$userId:DateCreated:true"
            val otherUserKey = "lib_playlists:${userId}2:SortName:false"
            val albumsKey = "lib_albums:$userId:SortName:false"
            for (key in listOf(otherSortKey, otherUserKey, albumsKey)) {
                LibraryCache.put(key, listOf(existing))
                PagedLibrary.put(key, listOf(existing), total = 1)
            }
            LibraryCache.put("home_playlists:$userId", listOf(existing))
            server.enqueue(MockResponse().setBody("""{"Id":"new"}"""))

            val created = app.container.media.createPlaylist(userId, "New playlist", listOf("track"))

            assertNull(LibraryCache.peek("home_playlists:$userId"))
            for (key in listOf(activeKey, otherSortKey)) {
                assertNull("Stale first page: $key", LibraryCache.peek(key))
                assertNull("Stale accumulated pages: $key", PagedLibrary.get(key))
            }
            for (key in listOf(otherUserKey, albumsKey)) {
                assertEquals(listOf(existing), LibraryCache.peek(key))
                assertEquals(listOf(existing), PagedLibrary.get(key)?.items)
            }
            withTimeout(5000) { requests.receive() }.complete(
                ItemsResponse(items = listOf(existing, JellyfinItem(id = created.id, name = created.name, type = "Playlist")), totalRecordCount = 2)
            )
            val refreshed = withTimeout(5000) { model.state.first { it.items.size == 2 } }
            assertEquals(listOf("existing", "new"), refreshed.items.map { it.id })
        }
    }

    @Test
    fun `a playlist page requested before creation cannot overwrite the refreshed list`() = runBlocking {
        MockWebServer().use { server ->
            val userId = server.signIn()
            val existing = JellyfinItem(id = "existing", type = "Playlist")
            val model = loadPlaylists(existing, total = 2)
            model.loadNextPage()
            val stalePage = withTimeout(5000) { requests.receive() }
            val pageJob = model.viewModelScope.coroutineContext.job.children.last()
            server.enqueue(MockResponse().setBody("""{"Id":"new"}"""))

            try {
                val created = app.container.media.createPlaylist(userId, "New playlist", listOf("track"))
                val createdWire = JellyfinItem(id = created.id, name = created.name, type = "Playlist")
                val expectedWire = listOf(existing, createdWire)
                val expected = expectedWire.toDomainItems()
                val freshPage = withTimeout(5000) { requests.receive() }
                val refreshJob = model.viewModelScope.coroutineContext.job.children.last()
                freshPage.complete(ItemsResponse(items = expectedWire, totalRecordCount = 2))
                withTimeout(5000) { refreshJob.join() }
                val refreshed = model.state.value
                assertEquals(expected, refreshed.items)

                // loadNextPage owns a separate job; reloading doesn't cancel it.
                assertTrue(pageJob.isActive)
                stalePage.complete(ItemsResponse(items = listOf(JellyfinItem(id = "stale", type = "MusicAlbum")), totalRecordCount = 2))
                withTimeout(5000) { pageJob.join() }
                assertEquals(expected, model.state.value.items)
                assertEquals(expectedWire, LibraryCache.peek(refreshed.cacheKey))
                assertEquals(expectedWire, PagedLibrary.get(refreshed.cacheKey)?.items)
            } finally {
                stalePage.complete(ItemsResponse())
            }
        }
    }

    @Test
    fun `returning to Liked after a favorite change fetches the current list`() = runBlocking {
        MockWebServer().use { server ->
            val userId = server.signIn()
            server.enqueue(MockResponse().setBody("""
                {"Items":[{"Id":"liked-track","Type":"Audio","UserData":{"IsFavorite":true}}],"TotalRecordCount":1}
            """.trimIndent()))
            val model = model("Tracks", likedOnly = true)
            withTimeout(5000) {
                model.viewModelScope.coroutineContext.job.children.toList().forEach { it.join() }
            }
            val likedTrack = model.state.value.items.single()
            assertEquals("liked-track", likedTrack.id)
            val key = model.state.value.cacheKey
            val likedWire = JellyfinItem(
                id = "liked-track", type = "Audio",
                userData = com.hans.aura.data.UserData(isFavorite = true)
            )
            assertEquals(listOf(likedWire), PagedLibrary.get(key)?.items)
            val filter = server.takeRequest(5, TimeUnit.SECONDS)?.requestUrl?.queryParameter("Filters")
            assertEquals("IsFavorite", filter)

            server.enqueue(MockResponse().setBody("""{"IsFavorite":false}"""))
            assertEquals(false, Favorites.toggle(app.jellyfin.api, userId, likedTrack))
            assertNull(LibraryCache.peek(key))
            assertNull(PagedLibrary.get(key))

            server.enqueue(MockResponse().setBody("""{"Items":[],"TotalRecordCount":0}"""))
            model.refreshLikedIfEvicted()
            val refreshed = withTimeout(5000) { model.state.first { !it.loading && it.total == 0 } }
            assertTrue(refreshed.items.isEmpty())
        }
    }

    @Test
    fun `refreshing Liked keeps the previously loaded range available for focus restoration`() = runBlocking {
        MockWebServer().use { server ->
            val userId = server.signIn()
            val key = "lib_liked:$userId:SortName:false"
            val tracks = List(65) { JellyfinItem(id = "track-$it", type = "Audio") }
            PagedLibrary.put(key, tracks, total = 90)
            val model = model("Tracks", likedOnly = true)
            assertEquals(tracks.toDomainItems(), model.state.value.items)

            PagedLibrary.evictWhere { it == key }
            model.refreshLikedIfEvicted()
            assertEquals(tracks.toDomainItems(), model.state.value.items)
            val request = server.takeRequest(5, TimeUnit.SECONDS)?.requestUrl
            assertEquals("0", request?.queryParameter("StartIndex"))
            assertEquals("65", request?.queryParameter("Limit"))

            val remaining = tracks.drop(1) + JellyfinItem(id = "track-65", type = "Audio")
            val itemsJson = remaining.joinToString(",") { """{"Id":"${it.id}","Type":"Audio"}""" }
            server.enqueue(MockResponse().setBody("""{"Items":[$itemsJson],"TotalRecordCount":89}"""))
            val refreshed = withTimeout(5000) { model.state.first { !it.loading && it.total == 89 } }
            assertEquals(remaining.map { it.id }, refreshed.items.map { it.id })
        }
    }

    @Test
    fun `evicted accumulated page reloads the displayed and requested pages so pagination continues`() = runBlocking {
        val model = model()
        val firstPage = (0 until 60).map { JellyfinItem(id = "$it", type = "MusicAlbum") }
        val live = (0 until 180).map { JellyfinItem(id = "$it", type = "MusicAlbum") }
        withTimeout(5000) { queries.receive() }
        withTimeout(5000) { requests.receive() }.complete(ItemsResponse(items = firstPage, totalRecordCount = live.size))
        withTimeout(5000) { model.state.first { it.items.size == 60 && !it.loading } }

        PagedLibrary.evictWhere { it.startsWith(model.state.value.cacheKey) }
        model.loadNextPage()
        val query = withTimeout(5000) { queries.receive() }
        val response = withTimeout(5000) { requests.receive() }
        try {
            assertEquals(0, query.startIndex)
            assertEquals(120, query.limit)
        } finally {
            response.complete(ItemsResponse(
                items = live.drop(query.startIndex).take(query.limit),
                totalRecordCount = live.size
            ))
        }
        withTimeout(5000) { model.state.first { !it.loading } }
        assertEquals(live.take(120).map { it.id }, model.state.value.items.map { it.id })
        assertTrue(model.state.value.items.size < model.state.value.total)
    }

    private fun MockWebServer.signIn(): String = "user".also { userId ->
        app.jellyfin.auth.installSession(Session(url("/").toString(), userId, "Test", "token", "device"))
    }

    private suspend fun loadPlaylists(item: JellyfinItem, total: Int = 1): LibraryViewModel {
        val model = model("Playlists")
        val response = withTimeout(5000) { requests.receive() }
        val loadJob = model.viewModelScope.coroutineContext.job.children.first()
        response.complete(ItemsResponse(items = listOf(item), totalRecordCount = total))
        withTimeout(5000) { loadJob.join() }
        assertEquals(listOf(item.toDomainItem()), model.state.value.items)
        return model
    }

    private fun model(kind: String = "Albums", likedOnly: Boolean = false): LibraryViewModel = ViewModelProvider(
        object : ViewModelStoreOwner { override val viewModelStore = store },
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                LibraryViewModel(app, kind, likedOnly) as T
        }
    )[LibraryViewModel::class.java]
}
