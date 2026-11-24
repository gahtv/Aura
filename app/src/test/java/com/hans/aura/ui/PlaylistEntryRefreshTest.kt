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
import com.hans.aura.data.PlaylistEditException
import com.hans.aura.data.PlaylistEditOutcome
import com.hans.aura.data.Session
import com.hans.aura.data.SourceQueries
import com.hans.aura.model.Playlist
import com.hans.aura.ui.collection.CollectionViewModel
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.HttpException
import retrofit2.Response

/**
 * Playlist edits must never resolve an occurrence against a list the server
 * has already changed.
 *
 * Jellyfin 10.11 reports `PlaylistItemId` as the linked track id (identical for
 * every duplicate) and removes every child matching that id, so deleting by
 * "the clicked entry id" wipes all copies of a duplicated track. The safe path
 * is the occurrence-based rewrite; its window is closed by refusing removal
 * until the post-edit refresh has landed:
 *
 *  - a playlist opened while an edit is pending skips `LibraryCache`, shows
 *    Loading, and keeps removal disabled through the pre-edit fetch;
 *  - the completion event refetches the post-edit order before removal is
 *    re-enabled;
 *  - a failed edit never re-enables a reload that would hide its error.
 */
@RunWith(RobolectricTestRunner::class)
class PlaylistEntryRefreshTest {

    private lateinit var app: AuraApp
    private lateinit var originalSource: MusicSource

    @OptIn(ExperimentalCoroutinesApi::class)
    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.jellyfin.auth.save(Session("http://localhost", "u", "Test", "token", "device"))
        Dispatchers.setMain(Dispatchers.Unconfined)
        LibraryCache.clear()
        originalSource = requireNotNull(MusicSources.current)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @After
    fun tearDown() {
        app.container.mutations.reset()
        MusicSources.install(originalSource)
        app.jellyfin.auth.clear()
        LibraryCache.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `reopened playlist shows no stale list and refreshes before the next removal`() = runBlocking {
        val fake = PlaylistFakeSource(originalSource, PLAYLIST, listOf("A", "B", "A", "C", "A"))
        MusicSources.install(fake)

        // First open: normal cache-backed load of the current server list.
        val firstStore = ViewModelStore()
        val first = newViewModel(firstStore)
        withTimeout(5_000) { first.state.first { it.tracks != null } }
        assertEquals(listOf("A", "B", "A", "C", "A"), first.state.value.tracks!!.map { it.id })
        assertFalse(first.state.value.editInFlight)
        assertNotNull("first load must seed the fake cache", SourceQueries.cachedTracks("col:$PLAYLIST"))

        val readGate = CountDownLatch(1)
        val removalGate = CountDownLatch(1)
        fake.readGate = readGate
        fake.removalGate = removalGate

        first.removeFromPlaylist("A", 0)
        assertTrue("launching a removal must disable it until the refresh", first.state.value.editInFlight)
        assertTrue("removal must reach the source", fake.removalStarted.await(5, TimeUnit.SECONDS))

        // Pop the playlist: Navigation 3 clears the entry's ViewModelStore.
        firstStore.clear()

        // Reopen while the removal is still in flight. The cache still holds
        // A B A C A; the new screen must not seed it and must not offer Remove.
        val secondStore = ViewModelStore()
        val second = newViewModel(secondStore)
        assertNull("pending edit: no cached tracks may be seeded", second.state.value.tracks)
        assertTrue("pending edit: removal stays disabled", second.state.value.editInFlight)
        awaitUntil { fake.playlistReads.get() >= 2 }
        assertNull("Loading stays until the forced fetch returns", second.state.value.tracks)
        assertTrue(
            "the pending marker must still be set while the removal is in flight",
            app.container.mutations.playlistEditPending(PLAYLIST)
        )

        // Release the in-flight read: it is a server fetch, not the cache.
        readGate.countDown()
        withTimeout(5_000) { second.state.first { it.tracks != null } }
        val pendingTracks = second.state.value.tracks!!
        assertEquals(listOf("A", "B", "A", "C", "A"), pendingTracks.map { it.id })
        assertTrue(
            "tracks must come from the forced server read, not LibraryCache",
            pendingTracks.all { it.name.startsWith("server:") }
        )
        assertTrue("a pre-edit fetch must not re-enable removal", second.state.value.editInFlight)

        // The server applies "remove the first A"; the completion event must
        // make the reopened screen drop the stale list.
        removalGate.countDown()
        assertTrue("removal must complete", fake.removalCompleted.await(5, TimeUnit.SECONDS))
        awaitUntil {
            second.state.value.tracks?.map { it.id } == listOf("B", "A", "C", "A") &&
                !second.state.value.editInFlight
        }

        // The refreshed list is what occurrence 1 resolves against: in
        // B A C A it is the A after C. The stale pre-edit list A B A C A
        // would have resolved occurrence 1 to the A after B instead.
        second.removeFromPlaylist("A", 1)
        awaitUntil { fake.serverTrackIds() == listOf("B", "A", "C") }
        assertEquals(listOf("A" to 0, "A" to 1), fake.removals.toList())
        secondStore.clear()
    }

    @Test
    fun `failed removal clears the pending state, keeps its error and does not reload`() = runBlocking {
        val fake = PlaylistFakeSource(originalSource, PLAYLIST, listOf("A", "B"))
        MusicSources.install(fake)

        val store = ViewModelStore()
        val viewModel = newViewModel(store)
        withTimeout(5_000) { viewModel.state.first { it.tracks != null } }
        val readsBefore = fake.playlistReads.get()
        fake.removalFailure = IllegalStateException("403 Forbidden")

        viewModel.removeFromPlaylist("A", 0)
        // The failure path clears editInFlight from the completion stream, so
        // reaching both states proves the event was handled.
        withTimeout(5_000) { viewModel.state.first { it.editError != null && !it.editInFlight } }
        assertEquals("a failed edit must not publish a reload", readsBefore, fake.playlistReads.get())
        assertEquals(listOf("A", "B"), fake.serverTrackIds())
        assertFalse(app.container.mutations.playlistEditPending(PLAYLIST))

        // A later successful reload keeps the edit error: it is not a load error.
        val editError = viewModel.state.value.editError
        viewModel.refresh()
        awaitUntil { fake.playlistReads.get() == readsBefore + 1 }
        assertEquals(editError, viewModel.state.value.editError)
        assertNull(viewModel.state.value.error)
        assertFalse(viewModel.state.value.editInFlight)
        store.clear()
    }

    @Test
    fun `lost removal response stays disabled until the refreshed order lands`() = runBlocking {
        val fake = PlaylistFakeSource(originalSource, PLAYLIST, listOf("A", "A", "B"))
        MusicSources.install(fake)

        val store = ViewModelStore()
        val viewModel = newViewModel(store)
        withTimeout(5_000) { viewModel.state.first { it.tracks != null } }
        assertNotNull("precondition: the load seeded col:<playlistId>", fake.cached("col:$PLAYLIST"))
        val readsBefore = fake.playlistReads.get()

        // The server applies the edit, then the response is lost. Hold the
        // reconciling read open so the in-between UI state is observable.
        val reloadGate = CountDownLatch(1)
        fake.readGate = reloadGate
        fake.removalUnknown = true
        viewModel.removeFromPlaylist("A", 0)

        assertTrue("the write must commit", fake.removalCompleted.await(5, TimeUnit.SECONDS))
        awaitUntil { fake.playlistReads.get() == readsBefore + 1 }
        assertTrue(
            "removal must stay disabled until the new order is confirmed",
            viewModel.state.value.editInFlight
        )
        assertNotNull("an unknown outcome must surface an edit message", viewModel.state.value.editError)
        assertNull("unknown outcome must evict the stale list", fake.cached("col:$PLAYLIST"))

        reloadGate.countDown()
        awaitUntil {
            viewModel.state.value.tracks?.map { it.id } == listOf("A", "B") &&
                !viewModel.state.value.editInFlight
        }
        assertNull(
            "the transient refreshing message must go once the list is re-read",
            viewModel.state.value.editError
        )
        store.clear()
    }

    @Test
    fun `a pre-write failure is definite and does not reconcile`() = runBlocking {
        val fake = PlaylistFakeSource(originalSource, PLAYLIST, listOf("A", "B"))
        MusicSources.install(fake)

        val store = ViewModelStore()
        val viewModel = newViewModel(store)
        withTimeout(5_000) { viewModel.state.first { it.tracks != null } }
        val readsBefore = fake.playlistReads.get()

        // The data layer saw the initial GET fail: no write was sent, so the
        // list on screen is still exactly what the server has.
        fake.removalFailure = PlaylistEditException(
            PlaylistEditOutcome.FAILED,
            IOException("timeout during the pre-write read")
        )
        viewModel.removeFromPlaylist("A", 0)

        withTimeout(5_000) { viewModel.state.first { it.editError != null && !it.editInFlight } }
        assertEquals("a definite failure must not publish a reload", readsBefore, fake.playlistReads.get())
        assertEquals(listOf("A", "B"), viewModel.state.value.tracks!!.map { it.id })
        assertTrue(
            "the definitive failure stays visible",
            viewModel.state.value.editError!!.startsWith("Couldn't remove from playlist")
        )
        assertFalse(app.container.mutations.playlistEditPending(PLAYLIST))
        store.clear()
    }

    @Test
    fun `a playlist reopened before the unknown-outcome refresh lands fetches instead of seeding`() =
        runBlocking {
            val fake = PlaylistFakeSource(originalSource, PLAYLIST, listOf("A", "A", "B"))
            MusicSources.install(fake)

            val firstStore = ViewModelStore()
            val first = newViewModel(firstStore)
            withTimeout(5_000) { first.state.first { it.tracks != null } }

            // Hold the reconciliation open so the cache stays evicted at the
            // moment the second screen is built.
            val reloadGate = CountDownLatch(1)
            fake.readGate = reloadGate
            fake.removalUnknown = true
            first.removeFromPlaylist("A", 0)
            assertTrue("the write must commit", fake.removalCompleted.await(5, TimeUnit.SECONDS))
            awaitUntil { fake.playlistReads.get() >= 2 }
            assertNull("the unknown outcome must evict the stale list", fake.cached("col:$PLAYLIST"))

            // Pop, then reopen: the cache read is null, so init has to fetch.
            firstStore.clear()
            val secondStore = ViewModelStore()
            val second = newViewModel(secondStore)
            assertNull("a reopened screen must not seed an evicted list", second.state.value.tracks)
            assertFalse(second.state.value.editInFlight)

            reloadGate.countDown()
            withTimeout(5_000) { second.state.first { it.tracks != null } }
            assertEquals(listOf("A", "B"), second.state.value.tracks!!.map { it.id })
            assertTrue(
                "the reopened screen must read the server, not seed a stale list",
                second.state.value.tracks!!.all { it.name.startsWith("server:") }
            )
            secondStore.clear()
        }

    @Test
    fun `a failed reconciliation keeps removal disabled until retry succeeds`() = runBlocking {
        val fake = PlaylistFakeSource(originalSource, PLAYLIST, listOf("A", "A", "B"))
        MusicSources.install(fake)

        val store = ViewModelStore()
        val viewModel = newViewModel(store)
        withTimeout(5_000) { viewModel.state.first { it.tracks != null } }

        fake.removalUnknown = true
        fake.readFailure = IllegalStateException("offline")
        viewModel.removeFromPlaylist("A", 0)
        awaitUntil {
            viewModel.state.value.editError != null &&
                fake.playlistReads.get() >= 2 &&
                viewModel.state.value.tracks?.map { it.id } != listOf("A", "B")
        }

        assertTrue("the owed refresh did not land: removal stays disabled", viewModel.state.value.editInFlight)
        assertNull("a refresh failure with rows on screen is not a load error", viewModel.state.value.error)

        // The banner's Retry is the recovery path.
        fake.readFailure = null
        viewModel.retry()
        awaitUntil {
            viewModel.state.value.tracks?.map { it.id } == listOf("A", "B") &&
                !viewModel.state.value.editInFlight
        }
        assertNull(
            "the retry's successful load clears the transient refreshing message",
            viewModel.state.value.editError
        )
        store.clear()
    }

    @Test
    fun `a rejected removal keeps the old list and does not reload`() = runBlocking {
        val fake = PlaylistFakeSource(originalSource, PLAYLIST, listOf("A", "B"))
        MusicSources.install(fake)

        val store = ViewModelStore()
        val viewModel = newViewModel(store)
        withTimeout(5_000) { viewModel.state.first { it.tracks != null } }
        val readsBefore = fake.playlistReads.get()

        fake.removalFailure = HttpException(
            Response.error<Unit>(403, "Forbidden".toResponseBody("text/plain".toMediaType()))
        )
        viewModel.removeFromPlaylist("A", 0)

        // A definite rejection commits nothing: no reload, old rows stay.
        withTimeout(5_000) { viewModel.state.first { it.editError != null && !it.editInFlight } }
        assertEquals(readsBefore, fake.playlistReads.get())
        assertEquals(listOf("A", "B"), viewModel.state.value.tracks!!.map { it.id })
        assertNotNull("the rejection stays visible as an edit error", viewModel.state.value.editError)
        store.clear()
    }

    @Test
    fun `removal is refused without a write while another edit is pending`() = runBlocking {
        val fake = PlaylistFakeSource(originalSource, PLAYLIST, listOf("A", "B"))
        MusicSources.install(fake)

        val store = ViewModelStore()
        val viewModel = newViewModel(store)
        withTimeout(5_000) { viewModel.state.first { it.tracks != null } }

        val removalGate = CountDownLatch(1)
        fake.removalGate = removalGate
        viewModel.removeFromPlaylist("A", 0)
        assertTrue(fake.removalStarted.await(5, TimeUnit.SECONDS))

        // A menu opened before the edit started can still invoke the action;
        // it must be refused, not resolved against the pre-edit ordinal.
        viewModel.removeFromPlaylist("B", 0)
        assertEquals("Playlist is still updating", viewModel.state.value.editError)
        assertEquals("the refused call must not start a second write", 1, fake.removals.size)

        removalGate.countDown()
        assertTrue(fake.removalCompleted.await(5, TimeUnit.SECONDS))
        awaitUntil {
            viewModel.state.value.tracks?.map { it.id } == listOf("B") &&
                !viewModel.state.value.editInFlight
        }
        assertEquals(listOf("A" to 0), fake.removals.toList())
        store.clear()
    }

    @Test
    fun `successful removal evicts the cached playlist before the completion refresh`() = runBlocking {
        val fake = PlaylistFakeSource(originalSource, PLAYLIST, listOf("A", "B"))
        MusicSources.install(fake)

        val store = ViewModelStore()
        val viewModel = newViewModel(store)
        withTimeout(5_000) { viewModel.state.first { it.tracks != null } }
        assertNotNull("precondition: the load seeded col:<playlistId>", fake.cached("col:$PLAYLIST"))

        // Hold the completion refetch so the eviction window is observable
        // without racing the reload that re-seeds the cache.
        val reloadGate = CountDownLatch(1)
        fake.readGate = reloadGate
        viewModel.removeFromPlaylist("A", 0)
        assertTrue(fake.removalCompleted.await(5, TimeUnit.SECONDS))
        awaitUntil { fake.playlistReads.get() >= 2 }
        assertNull("successful removal must evict col:<playlistId>", fake.cached("col:$PLAYLIST"))

        reloadGate.countDown()
        awaitUntil { viewModel.state.value.tracks?.map { it.id } == listOf("B") }
        assertNotNull("the completion reload re-seeds the post-edit list", fake.cached("col:$PLAYLIST"))
        store.clear()
    }

    private fun newViewModel(store: ViewModelStore = ViewModelStore()): CollectionViewModel {
        val playlist = Playlist(id = PLAYLIST, name = "playlist")
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                CollectionViewModel(app, playlist) as T
        }
        return ViewModelProvider(store, factory)[CollectionViewModel::class.java]
    }

    private suspend fun awaitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!condition()) delay(10)
        }
    }

    /**
     * A source with Jellyfin 10.11's entry identity semantics: every duplicate
     * row carries the track id, so the occurrence path is the only safe one.
     * Reads/removals are observable and can be suspended on latches.
     */
    private class PlaylistFakeSource(
        base: MusicSource,
        private val playlistId: String,
        trackIds: List<String>
    ) : MusicSource by base {

        override val id: String = "playlist-entry-refresh-test"

        private val lock = Any()
        private val entries = trackIds.toMutableList()
        private var cachedSnapshot: List<JellyfinItem>? = null

        val removals = CopyOnWriteArrayList<Pair<String, Int>>()
        val playlistReads = AtomicInteger()
        val removalStarted = CountDownLatch(1)
        val removalCompleted = CountDownLatch(1)

        /** When set, [playlistItems] suspends until it is released. */
        @Volatile var readGate: CountDownLatch? = null

        /** When set, [removeFromPlaylist] suspends until it is released. */
        @Volatile var removalGate: CountDownLatch? = null

        /** When set, [playlistItems] fails after counting the read. */
        @Volatile var readFailure: Throwable? = null

        /** When set, [removeFromPlaylist] fails after [removalGate] releases. */
        @Volatile var removalFailure: Throwable? = null

        /**
         * When true, [removeFromPlaylist] applies the edit, evicts the cache,
         * then throws [IOException] — a committed write whose response was
         * lost, exactly what MediaRepository's eviction-before-rethrow covers.
         */
        @Volatile var removalUnknown: Boolean = false

        fun serverTrackIds(): List<String> = synchronized(lock) { entries.toList() }

        override suspend fun cached(prefix: String): List<JellyfinItem>? {
            if (prefix != "col:$playlistId") return null
            // A distinct name proves whether a screen read the cache or the
            // server, even when both hold the same ids.
            return synchronized(lock) {
                cachedSnapshot?.map { it.copy(name = "cached:${it.id}") }
            }
        }

        override fun cache(prefix: String, items: List<JellyfinItem>) {
            if (prefix == "col:$playlistId") synchronized(lock) { cachedSnapshot = items }
        }

        override suspend fun item(itemId: String): JellyfinItem? = null

        override suspend fun atmosAlbumIds(): Set<String> = emptySet()

        override suspend fun playlistItems(playlistId: String): List<JellyfinItem> {
            playlistReads.incrementAndGet()
            readGate?.await(10, TimeUnit.SECONDS)
            readFailure?.let { throw it }
            return synchronized(lock) {
                entries.map {
                    // 10.11: PlaylistItemId is the linked child's ItemId.
                    JellyfinItem(
                        id = it,
                        name = "server:$it",
                        type = "Audio",
                        playlistItemId = it
                    )
                }
            }
        }

        override suspend fun removeFromPlaylist(playlistId: String, itemId: String, occurrence: Int) {
            removals.add(itemId to occurrence)
            removalStarted.countDown()
            removalGate?.await(10, TimeUnit.SECONDS)
            removalFailure?.let { throw it }
            val lostResponse = synchronized(lock) {
                val matches = entries.indices.filter { entries[it] == itemId }
                val index = matches.getOrNull(occurrence)
                    ?: throw IllegalStateException("Playlist entry no longer exists")
                entries.removeAt(index)
                // Mirrors MediaRepository: the cache is evicted once the
                // server applied the edit (and also when the response is lost).
                cachedSnapshot = null
                removalUnknown
            }
            removalCompleted.countDown()
            if (lostResponse) throw IOException("Response lost after the server applied the edit")
        }
    }

    private companion object {
        const val PLAYLIST = "p"
    }
}
