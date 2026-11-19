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
                return withContext(NonCancellable) { response.await() }
            }
        })
    }

    @After
    fun tearDown() {
        store.clear()
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

    private fun model(): LibraryViewModel = ViewModelProvider(
        object : ViewModelStoreOwner { override val viewModelStore = store },
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                LibraryViewModel(app, "Albums") as T
        }
    )[LibraryViewModel::class.java]
}
