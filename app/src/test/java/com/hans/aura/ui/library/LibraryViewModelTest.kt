package com.hans.aura.ui.library

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.hans.aura.AuraApp
import com.hans.aura.data.AtmosFilter
import com.hans.aura.data.ItemsResponse
import com.hans.aura.data.JellyfinItem
import com.hans.aura.data.LibraryCache
import com.hans.aura.data.LibraryFilter
import com.hans.aura.data.MusicSource
import com.hans.aura.data.MusicSources
import com.hans.aura.data.PagedLibrary
import com.hans.aura.data.Session
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LibraryViewModelTest {
    private val store = ViewModelStore()
    private val requests = Channel<CompletableDeferred<ItemsResponse>>(Channel.UNLIMITED)
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
            override suspend fun atmosAlbumIds(): Set<String> = emptySet()
            override suspend fun browse(query: LibraryFilter.ItemsQuery, cachePrefix: String?): ItemsResponse {
                val response = CompletableDeferred<ItemsResponse>()
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
        second.complete(ItemsResponse(items = listOf(JellyfinItem(id = "new")), totalRecordCount = 1))
        withTimeout(5000) { model.state.first { it.items.isNotEmpty() && !it.loading } }
        assertEquals(listOf("new"), model.state.value.items.map { it.id })
        first.complete(ItemsResponse(items = listOf(JellyfinItem(id = "stale")), totalRecordCount = 1))
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
            ItemsResponse(items = listOf(JellyfinItem(id = "recovered")), totalRecordCount = 1)
        )
        withTimeout(5000) { model.state.first { !it.loading } }
        assertEquals(listOf("recovered"), model.state.value.items.map { it.id })
        assertNull(model.state.value.error)
    }

    @Test
    fun `creating a playlist refreshes a previously loaded Playlists tab`() = runBlocking {
        MockWebServer().use { server ->
            val userId = server.signIn()
            val existing = JellyfinItem(id = "existing")
            val model = loadPlaylists(existing)
            val activeKey = model.state.value.cacheKey
            assertEquals(listOf(existing), LibraryCache.get(activeKey))
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

            assertNull(LibraryCache.get("home_playlists:$userId"))
            for (key in listOf(activeKey, otherSortKey)) {
                assertNull("Stale first page: $key", LibraryCache.get(key))
                assertNull("Stale accumulated pages: $key", PagedLibrary.get(key))
            }
            for (key in listOf(otherUserKey, albumsKey)) {
                assertEquals(listOf(existing), LibraryCache.get(key))
                assertEquals(listOf(existing), PagedLibrary.get(key)?.items)
            }
            withTimeout(5000) { requests.receive() }.complete(
                ItemsResponse(items = listOf(existing, created), totalRecordCount = 2)
            )
            val refreshed = withTimeout(5000) { model.state.first { it.items.size == 2 } }
            assertEquals(listOf("existing", "new"), refreshed.items.map { it.id })
        }
    }

    @Test
    fun `a playlist page requested before creation cannot overwrite the refreshed list`() = runBlocking {
        MockWebServer().use { server ->
            val userId = server.signIn()
            val existing = JellyfinItem(id = "existing")
            val model = loadPlaylists(existing, total = 2)
            model.loadNextPage()
            val stalePage = withTimeout(5000) { requests.receive() }
            val pageJob = model.viewModelScope.coroutineContext.job.children.last()
            server.enqueue(MockResponse().setBody("""{"Id":"new"}"""))

            try {
                val created = app.container.media.createPlaylist(userId, "New playlist", listOf("track"))
                val expected = listOf(existing, created)
                val freshPage = withTimeout(5000) { requests.receive() }
                val refreshJob = model.viewModelScope.coroutineContext.job.children.last()
                freshPage.complete(ItemsResponse(items = expected, totalRecordCount = 2))
                withTimeout(5000) { refreshJob.join() }
                val refreshed = model.state.value
                assertEquals(expected, refreshed.items)

                // loadNextPage owns a separate job; reloading doesn't cancel it.
                assertTrue(pageJob.isActive)
                stalePage.complete(ItemsResponse(items = listOf(JellyfinItem(id = "stale")), totalRecordCount = 2))
                withTimeout(5000) { pageJob.join() }
                assertEquals(expected, model.state.value.items)
                assertEquals(expected, LibraryCache.get(refreshed.cacheKey))
                assertEquals(expected, PagedLibrary.get(refreshed.cacheKey)?.items)
            } finally {
                stalePage.complete(ItemsResponse())
            }
        }
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
        assertEquals(listOf(item), model.state.value.items)
        return model
    }

    private fun model(kind: String = "Albums"): LibraryViewModel = ViewModelProvider(
        object : ViewModelStoreOwner { override val viewModelStore = store },
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                LibraryViewModel(app, kind) as T
        }
    )[LibraryViewModel::class.java]
}
