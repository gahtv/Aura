package com.hans.aura.data

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-playlist write gate is what keeps a duplicate removal's
 * read-modify-write from erasing a concurrent add/move. These tests prove the
 * gate's contract directly: same id serialized, different ids independent,
 * and every exit path (normal, throw, cancel) releases it.
 */
class PlaylistWritesTest {

    private val writes = PlaylistWrites()

    @Test
    fun `same playlist runs one block at a time`() = runBlocking {
        val events = CopyOnWriteArrayList<String>()
        val firstIn = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondIn = CompletableDeferred<Unit>()

        val first = launch(Dispatchers.Default) {
            writes.withPlaylist("p") {
                events += "in-1"
                firstIn.complete(Unit)
                releaseFirst.await()
                events += "out-1"
            }
        }
        withTimeout(5_000) { firstIn.await() }

        val second = launch(Dispatchers.Default) {
            writes.withPlaylist("p") {
                events += "in-2"
                secondIn.complete(Unit)
                events += "out-2"
            }
        }

        // Plenty of time for a broken (unlocked) implementation to overlap.
        val overlapped = withTimeoutOrNull(500) { secondIn.await() }
        assertNull("a second write entered while the first still held the playlist", overlapped)
        assertEquals(listOf("in-1"), events.toList())

        releaseFirst.complete(Unit)
        withTimeout(5_000) { secondIn.await() }
        joinAll(first, second)
        assertEquals(listOf("in-1", "out-1", "in-2", "out-2"), events.toList())
    }

    @Test
    fun `different playlists do not wait for each other`() = runBlocking {
        val bothIn = CompletableDeferred<Unit>()
        val entered = AtomicInteger()

        suspend fun meet() {
            if (entered.incrementAndGet() == 2) bothIn.complete(Unit)
            // A single global lock would make one side wait here until the
            // other times out, leaving bothIn incomplete and failing the test.
            withTimeout(5_000) { bothIn.await() }
        }

        val a = launch(Dispatchers.Default) { writes.withPlaylist("a") { meet() } }
        val b = launch(Dispatchers.Default) { writes.withPlaylist("b") { meet() } }

        withTimeout(5_000) { bothIn.await() }
        joinAll(a, b)
        assertEquals(2, entered.get())
    }

    @Test
    fun `a throwing block releases the playlist`() = runBlocking {
        val error = runCatching {
            writes.withPlaylist("p") { throw IllegalStateException("boom") }
        }.exceptionOrNull()
        assertEquals("boom", error?.message)

        val ran = CompletableDeferred<Unit>()
        withTimeout(2_000) { writes.withPlaylist("p") { ran.complete(Unit) } }
        assertTrue("a throwing block must release the lock", ran.isCompleted)
    }

    @Test
    fun `a cancelled block releases the playlist`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.Default) {
            writes.withPlaylist("p") {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        withTimeout(5_000) { entered.await() }
        holder.cancelAndJoin()

        val ran = CompletableDeferred<Unit>()
        withTimeout(2_000) { writes.withPlaylist("p") { ran.complete(Unit) } }
        assertTrue("a cancelled block must release the lock", ran.isCompleted)
    }
}
