package com.hans.aura.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.hans.aura.AuraApp
import com.hans.aura.data.JellyfinClient
import com.hans.aura.data.JellyfinItem
import com.hans.aura.data.JellyfinSource
import com.hans.aura.data.LibraryCache
import com.hans.aura.data.MediaRepository
import com.hans.aura.data.MusicSource
import com.hans.aura.data.MusicSources
import com.hans.aura.data.Session
import com.hans.aura.data.SourceQueries
import com.hans.aura.model.Playlist
import com.hans.aura.ui.collection.CollectionViewModel
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
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Playlist removal must delete the entry the user clicked, and a playlist
 * reopened while an earlier edit is still in flight must not act on the stale
 * cached list.
 *
 * Devin's A B A C A case: a stale screen resolved "remove the middle A" as
 * occurrence 1 against a list the server had already changed, which deleted
 * the A after C. Two coupled guarantees fix that:
 *
 *  - the row's `playlistItemId` is forwarded and deleted by identity, so a
 *    stale click targets a gone entry (a harmless failure) instead of a
 *    different duplicate;
 *  - a playlist opened while an edit is pending ignores `LibraryCache`,
 *    force-loads, and refetches again when the edit completes.
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
    fun `entry id removes the clicked entry and a missing id keeps the occurrence fallback`() = runBlocking {
        val fake = PlaylistFakeSource(originalSource, PLAYLIST, listOf("A", "B", "A"))
        MusicSources.install(fake)
        val viewModel = newViewModel()

        withTimeout(5_000) { viewModel.state.first { it.tracks != null } }
        val firstA = viewModel.state.value.tracks.orEmpty().first { it.id == "A" }
        assertEquals("entry-A-0", firstA.playlistItemId)

        viewModel.removeFromPlaylist("A", 0, firstA.playlistItemId)
        awaitUntil { fake.entryRemovals.size == 1 }
        assertEquals(listOf("entry-A-0"), fake.entryRemovals.toList())
        assertTrue("entry-id path must not resolve an occurrence", fake.occurrenceRemovals.isEmpty())

        // A row without an entry id (or an older backend) keeps the old path.
        viewModel.removeFromPlaylist("A", 1, playlistItemId = null)
        awaitUntil { fake.occurrenceRemovals.isNotEmpty() }
        assertEquals(listOf("A" to 1), fake.occurrenceRemovals.toList())
        assertEquals(listOf("entry-A-0"), fake.entryRemovals.toList())

        // A source that cannot address an entry falls back even when one is passed.
        val unsupported = object : MusicSource by fake {
            override val supportsPlaylistEntryRemoval = false
        }
        MusicSources.install(unsupported)
        SourceQueries.removeFromPlaylist(PLAYLIST, "B", 0, entryId = "entry-B-1")
        assertEquals(listOf("A" to 1, "B" to 0), fake.occurrenceRemovals.toList())
        assertEquals(listOf("entry-A-0"), fake.entryRemovals.toList())
    }

    @Test
    fun `reopened playlist ignores the stale cache while pending and refreshes on completion`() = runBlocking {
        val fake = PlaylistFakeSource(originalSource, PLAYLIST, listOf("A", "B", "A", "C", "A"))
        MusicSources.install(fake)

        // First open: normal cache-backed load of the current server list.
        val firstStore = ViewModelStore()
        val first = newViewModel(firstStore)
        withTimeout(5_000) { first.state.first { it.tracks != null } }
        assertEquals(listOf("A", "B", "A", "C", "A"), first.state.value.tracks!!.map { it.id })
        assertTrue("first load must be cached for the reopen", SourceQueries.cachedTracks("col:$PLAYLIST") != null)

        val entryOfFirstA = first.state.value.tracks!!.first().playlistItemId!!
        val readGate = CountDownLatch(1)
        val removalGate = CountDownLatch(1)
        fake.readGate = readGate
        fake.removalGate = removalGate

        first.removeFromPlaylist("A", 0, entryOfFirstA)
        assertTrue("removal must reach the source", fake.removalStarted.await(5, TimeUnit.SECONDS))

        // Pop the playlist: Navigation 3 clears the entry's ViewModelStore.
        firstStore.clear()

        // Reopen while the removal is still in flight. The cache still holds
        // A B A C A; the new screen must not seed it.
        val secondStore = ViewModelStore()
        val second = newViewModel(secondStore)
        assertNull("pending edit: no cached tracks may be seeded", second.state.value.tracks)
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

        // The server applies "remove the first A"; the completion event must
        // make the reopened screen drop the stale list.
        removalGate.countDown()
        assertTrue("removal must complete", fake.removalCompleted.await(5, TimeUnit.SECONDS))
        awaitUntil { second.state.value.tracks?.map { it.id } == listOf("B", "A", "C", "A") }

        // A B A C A redux: the middle A is now entry-A-2, not occurrence 1 of
        // the stale list (which would have been the A after C).
        val middleA = second.state.value.tracks!!.first { it.id == "A" }
        second.removeFromPlaylist("A", 1, middleA.playlistItemId)
        awaitUntil { fake.serverTrackIds() == listOf("B", "C", "A") }
        assertEquals(listOf("entry-A-0", "entry-A-2"), fake.entryRemovals.toList())
        assertTrue("entry-id removals never fall back to an ordinal", fake.occurrenceRemovals.isEmpty())
        secondStore.clear()
    }

    @Test
    fun `jellyfin source deletes an entry id directly without a pre read`() = runBlocking {
        val server = MockWebServer()
        server.start()
        val client = JellyfinClient(ApplicationProvider.getApplicationContext())
        client.auth.save(Session(server.url("/").toString(), "user", "test", "token", "device"))
        try {
            MusicSources.install(JellyfinSource(client, MediaRepository(client)))
            LibraryCache.clear()
            server.enqueue(MockResponse().setResponseCode(204))

            SourceQueries.removeFromPlaylist("p", "A", 7, entryId = "entry-a2")

            val request = server.takeRequest()
            assertEquals("DELETE", request.method)
            assertEquals("/Playlists/p/Items", request.requestUrl!!.encodedPath)
            assertEquals("entry-a2", request.requestUrl!!.queryParameter("entryIds"))
            assertNull(request.requestUrl!!.queryParameter("Ids"))
            assertEquals("no pre-read before an entry-id delete", 1, server.requestCount)
            assertNull("successful removal must evict col:p", LibraryCache.peek("col:p"))
        } finally {
            client.auth.clear()
            MusicSources.reset()
            server.shutdown()
        }
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
     * A source with real per-entry identity: the server list, its cache and
     * the two removal paths are all observable, and reads/removals can be
     * suspended on latches.
     */
    private class PlaylistFakeSource(
        base: MusicSource,
        private val playlistId: String,
        trackIds: List<String>
    ) : MusicSource by base {

        override val id: String = "playlist-entry-refresh-test"

        override val supportsPlaylistEntryRemoval: Boolean = true

        private data class Entry(val trackId: String, val entryId: String)

        private val lock = Any()
        private val entries = trackIds.mapIndexed { index, trackId ->
            Entry(trackId, "entry-$trackId-$index")
        }.toMutableList()
        private var cachedSnapshot: List<JellyfinItem>? = null

        val entryRemovals = CopyOnWriteArrayList<String>()
        val occurrenceRemovals = CopyOnWriteArrayList<Pair<String, Int>>()
        val playlistReads = AtomicInteger()
        val removalStarted = CountDownLatch(1)
        val removalCompleted = CountDownLatch(1)

        /** When set, [playlistItems] suspends until it is released. */
        @Volatile var readGate: CountDownLatch? = null

        /** When set, [removePlaylistEntry] suspends until it is released. */
        @Volatile var removalGate: CountDownLatch? = null

        fun serverTrackIds(): List<String> = synchronized(lock) { entries.map { it.trackId } }

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
            return synchronized(lock) {
                entries.map {
                    JellyfinItem(
                        id = it.trackId,
                        name = "server:${it.trackId}",
                        type = "Audio",
                        playlistItemId = it.entryId
                    )
                }
            }
        }

        override suspend fun removePlaylistEntry(playlistId: String, entryId: String) {
            entryRemovals.add(entryId)
            removalStarted.countDown()
            removalGate?.await(10, TimeUnit.SECONDS)
            synchronized(lock) {
                entries.removeAll { it.entryId == entryId }
                // Mirrors MediaRepository: the cache is evicted only after the
                // server applied the edit.
                cachedSnapshot = null
            }
            removalCompleted.countDown()
        }

        override suspend fun removeFromPlaylist(playlistId: String, itemId: String, occurrence: Int) {
            occurrenceRemovals.add(itemId to occurrence)
        }
    }

    private companion object {
        const val PLAYLIST = "p"
    }
}
