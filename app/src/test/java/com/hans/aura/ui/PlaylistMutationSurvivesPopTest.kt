package com.hans.aura.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.hans.aura.AuraApp
import com.hans.aura.data.JellyfinItem
import com.hans.aura.data.LibraryCache
import com.hans.aura.data.MusicSource
import com.hans.aura.data.MusicSources
import com.hans.aura.data.Session
import com.hans.aura.model.Playlist
import com.hans.aura.ui.collection.CollectionViewModel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Regression for playlist removal being cancelled by a NavEntry pop.
 *
 * PR #93 scoped detail ViewModels per Navigation 3 entry, so popping the
 * playlist clears its ViewModelStore after the exit transition. A removal
 * launched in `viewModelScope` dies with it — either before MediaRepository's
 * pre-read (write lost) or after the server applied the edit but before the
 * response (cache not evicted, so the removed track reappears). The mutation
 * must run in the session-owned scope instead.
 */
@RunWith(RobolectricTestRunner::class)
class PlaylistMutationSurvivesPopTest {

    private lateinit var app: AuraApp
    private lateinit var originalSource: MusicSource
    private val removalStarted = CountDownLatch(1)
    private val releaseRemoval = CountDownLatch(1)
    private val removalCompleted = CountDownLatch(1)

    @OptIn(ExperimentalCoroutinesApi::class)
    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.jellyfin.auth.save(Session("http://localhost", "u", "Test", "token", "device"))
        // viewModelScope needs a Main dispatcher. Default keeps the pop's
        // cancellation observable off the test thread instead of running the
        // removing coroutine inline (where the latch below would block us).
        Dispatchers.setMain(Dispatchers.Default)
        LibraryCache.clear()
        originalSource = requireNotNull(MusicSources.current)
        MusicSources.install(object : MusicSource by originalSource {
            override val id = "playlist-mutation-test"

            // The removal is the only path this test exercises; everything
            // CollectionViewModel.init asks for answers instantly.
            override suspend fun cached(prefix: String): List<JellyfinItem>? = null
            override fun cache(prefix: String, items: List<JellyfinItem>) {}
            override suspend fun item(itemId: String): JellyfinItem? = null
            override suspend fun playlistItems(playlistId: String): List<JellyfinItem> = emptyList()
            override suspend fun atmosAlbumIds(): Set<String> = emptySet()

            override suspend fun removeFromPlaylist(playlistId: String, itemId: String, occurrence: Int) {
                removalStarted.countDown()
                // The latch stands in for the server round-trip. The IO hop
                // makes the suspension cancellable, exactly like the real
                // repository call: a cancelled caller never reaches eviction.
                withContext(Dispatchers.IO) {
                    releaseRemoval.await(10, TimeUnit.SECONDS)
                }
                // MediaRepository evicts the collection cache after a
                // completed write; mirrored here so the test proves the
                // coroutine got past the server edit, not merely started.
                LibraryCache.evict("col:$playlistId")
                removalCompleted.countDown()
            }
        })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @After
    fun tearDown() {
        app.container.mutations.reset()
        releaseRemoval.countDown()
        MusicSources.install(originalSource)
        app.jellyfin.auth.clear()
        LibraryCache.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `removal finishes evicting after the playlist ViewModel is cleared`() {
        val store = ViewModelStore()
        val playlist = Playlist(id = "playlist", name = "playlist")
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                CollectionViewModel(app, playlist) as T
        }
        val viewModel = ViewModelProvider(store, factory)[CollectionViewModel::class.java]

        LibraryCache.put("col:playlist", listOf(JellyfinItem(id = "track", type = "Audio")))
        assertTrue("precondition: playlist tracks are cached", LibraryCache.peek("col:playlist") != null)

        viewModel.removeFromPlaylist("track", 0)
        assertTrue("removal must reach the source", removalStarted.await(5, TimeUnit.SECONDS))

        // What NavDisplay does when the playlist entry is popped: the entry's
        // ViewModelStore is cleared while the request is still in flight.
        store.clear()

        releaseRemoval.countDown()
        assertTrue(
            "removal must complete after the ViewModel is cleared",
            removalCompleted.await(5, TimeUnit.SECONDS)
        )
        assertNull("successful removal must evict col:<playlistId>", LibraryCache.peek("col:playlist"))
    }

    /**
     * A session change (profile switch / sign-out) is the one thing that is
     * allowed to stop an in-flight mutation: reset() cancels the old scope and
     * hands out a fresh one, so the stale profile can never commit.
     */
    @Test
    fun `reset cancels the in-flight mutation scope and replaces it`() = runBlocking {
        val mutations = app.container.mutations
        val oldScope = mutations.scope
        val started = CountDownLatch(1)
        val finished = AtomicBoolean(false)
        val job = oldScope.launch {
            started.countDown()
            delay(60_000)
            finished.set(true)
        }
        assertTrue("mutation must start", started.await(5, TimeUnit.SECONDS))

        mutations.reset()

        withTimeout(5_000) { job.join() }
        assertTrue("reset must cancel the old scope's job", job.isCancelled)
        assertFalse("a cancelled mutation must not run its tail", finished.get())
        assertFalse(oldScope.isActive)
        assertNotSame(oldScope, mutations.scope)
        assertTrue(mutations.scope.isActive)
    }
}
