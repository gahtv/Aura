package com.hans.aura.ui.home

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import com.hans.aura.AuraApp
import com.hans.aura.data.ItemsResponse
import com.hans.aura.data.JellyfinItem
import com.hans.aura.data.LibraryCache
import com.hans.aura.data.LibraryFilter
import com.hans.aura.data.MusicSource
import com.hans.aura.data.MusicSources
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HomeViewModelTest {
    private val store = ViewModelStore()
    private lateinit var app: AuraApp
    private val scanStarted = CompletableDeferred<Unit>()
    private val scan = CompletableDeferred<Set<String>>()
    private val recent = CompletableDeferred<ItemsResponse>()
    private val artists = CompletableDeferred<ItemsResponse>()
    private val playlists = CompletableDeferred<ItemsResponse>()
    private val albumQueries = AtomicInteger()
    private val album = JellyfinItem(id = "album")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        app = ApplicationProvider.getApplicationContext()
        LibraryCache.clear()
        val source = MusicSources.current!!
        MusicSources.install(object : MusicSource by source {
            override val hasPlayHistory = false
            override suspend fun atmosAlbumIds(): Set<String> {
                scanStarted.complete(Unit)
                return scan.await()
            }
            override suspend fun artists(sortBy: String, sortOrder: String, limit: Int, startIndex: Int) = artists.await()
            override suspend fun browse(query: LibraryFilter.ItemsQuery, cachePrefix: String?): ItemsResponse =
                if (query.includeItemTypes == "Playlist") playlists.await() else {
                    albumQueries.incrementAndGet()
                    recent.await()
                }
        })
    }

    @After
    fun tearDown() {
        store.clear()
        MusicSources.reset()
        LibraryCache.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `artists and playlists arrive while Atmos scan is pending`() = runBlocking {
        val model = model()
        recent.complete(ItemsResponse(items = listOf(album)))
        withTimeout(5000) { scanStarted.await() }
        artists.complete(ItemsResponse(items = listOf(JellyfinItem(id = "artist"))))
        playlists.complete(ItemsResponse(items = listOf(JellyfinItem(id = "playlist"))))
        val partial = withTimeout(5000) { model.state.first { it.artists != null && it.playlists != null } }
        assertEquals("artist", partial.artists!!.single().id)
        assertEquals("playlist", partial.playlists!!.single().id)
        assertNull(partial.atmosAlbums)
        assertTrue(partial.loading)
        scan.complete(setOf(album.id))
        val done = withTimeout(5000) { model.state.first { !it.loading } }
        assertEquals(listOf(album), done.atmosAlbums)
        assertEquals(1, albumQueries.get())
        assertNull(done.error)
    }

    @Test
    fun `scan starts before recent albums and waits for their zero-query match`() = runBlocking {
        val model = model()
        withTimeout(5000) { scanStarted.await() }
        scan.complete(setOf(album.id))
        artists.complete(ItemsResponse())
        playlists.complete(ItemsResponse())
        withTimeout(5000) { model.state.first { it.artists != null && it.playlists != null } }
        assertNull(model.state.value.atmosAlbums)
        assertTrue(model.state.value.loading)
        recent.complete(ItemsResponse(items = listOf(album)))
        val done = withTimeout(5000) { model.state.first { !it.loading } }
        assertEquals(listOf(album), done.atmosAlbums)
        assertEquals(1, albumQueries.get())
    }

    @Test
    fun `cached recent albums are reused by Atmos without an album query`() = runBlocking {
        val uid = app.jellyfin.auth.session?.userId ?: ""
        LibraryCache.put("home_new:$uid", listOf(album))
        val model = model()
        withTimeout(5000) { scanStarted.await() }
        scan.complete(setOf(album.id))
        artists.complete(ItemsResponse())
        playlists.complete(ItemsResponse())
        val done = withTimeout(5000) { model.state.first { !it.loading } }
        assertEquals(listOf(album), done.atmosAlbums)
        assertEquals(0, albumQueries.get())
    }

    @Test
    fun `failed shelf remains retryable while siblings finish`() = runBlocking {
        val model = model()
        recent.complete(ItemsResponse())
        artists.completeExceptionally(IllegalStateException("artists offline"))
        playlists.complete(ItemsResponse())
        scan.complete(emptySet())
        val done = withTimeout(5000) { model.state.first { !it.loading } }
        assertNull(done.artists)
        assertNull(LibraryCache.peek("home_artists:"))
        assertEquals(emptyList<JellyfinItem>(), done.playlists)
        assertEquals(emptyList<JellyfinItem>(), done.recentAlbums)
        assertEquals(emptyList<JellyfinItem>(), done.atmosAlbums)
        assertEquals("artists offline", done.error)
    }

    private fun model(): HomeViewModel = ViewModelProvider(
        object : ViewModelStoreOwner { override val viewModelStore = store },
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = HomeViewModel(app) as T
        }
    )[HomeViewModel::class.java]
}
