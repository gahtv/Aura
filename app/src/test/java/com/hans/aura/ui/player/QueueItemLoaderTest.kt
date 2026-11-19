package com.hans.aura.ui.player

import com.hans.aura.data.Fixtures
import com.hans.aura.data.JellyfinItem
import com.hans.aura.player.controller.QUEUE_ITEM_PARALLELISM
import com.hans.aura.player.controller.QueueItemLoader
import com.hans.aura.player.controller.QueueRegistry
import com.hans.aura.player.controller.fetchQueueItems
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QueueItemLoaderTest {
    private class Requests {
        val ids = mutableListOf<String>()
        private val pending = mutableMapOf<String, CompletableDeferred<JellyfinItem>>()
        var active = 0
            private set
        var peak = 0
            private set

        suspend fun fetch(id: String): JellyfinItem {
            ids += id
            active++
            peak = maxOf(peak, active)
            return try {
                pending.getOrPut(id) { CompletableDeferred() }.await()
            } finally {
                active--
            }
        }

        fun complete(id: String) {
            pending.remove(id)!!.complete(Fixtures.item(id))
        }

        fun fail(id: String, cause: IOException) {
            pending.remove(id)!!.completeExceptionally(cause)
        }
    }

    @Test
    fun `service fetch overlaps requests within the bound and keeps wire order`() = runTest {
        val requests = Requests()
        val ids = (0..QUEUE_ITEM_PARALLELISM).map(Int::toString)
        val permits = Semaphore(QUEUE_ITEM_PARALLELISM)
        val result = async { fetchQueueItems(ids, permits, requests::fetch) }
        runCurrent()
        assertEquals(QUEUE_ITEM_PARALLELISM, requests.active)

        requests.complete(ids[1])
        runCurrent()
        assertEquals(ids, requests.ids)
        for (id in ids.asReversed().filterNot { it == ids[1] }) requests.complete(id)

        assertEquals(ids, result.await().map { it.id })
        assertEquals(QUEUE_ITEM_PARALLELISM, requests.peak)
    }

    @Test
    fun `service failure aborts the cast and cancels sibling fetches`() = runTest {
        val requests = Requests()
        val cause = IOException("missing track")
        val permits = Semaphore(QUEUE_ITEM_PARALLELISM)
        val result = async {
            runCatching { fetchQueueItems(listOf("missing", "pending"), permits, requests::fetch) }
        }
        runCurrent()
        assertEquals(listOf("missing", "pending"), requests.ids)
        requests.fail("missing", cause)

        assertFailure(cause, result.await())
        assertEquals(0, requests.active)
    }

    @Test
    fun `overlapping queue snapshots and current track join one bounded request per ID`() = runTest {
        val registry = QueueRegistry()
        val loader = QueueItemLoader(backgroundScope, registry)
        val requests = Requests()
        val ids = (0..QUEUE_ITEM_PARALLELISM).map(Int::toString)
        val first = async { loader.loadAll(ids, requests::fetch) }
        val second = async { loader.loadAll(ids.reversed() + ids.first(), requests::fetch) }
        val current = async { loader.load(ids.first()) { requests.fetch(ids.first()) } }
        runCurrent()
        assertEquals(QUEUE_ITEM_PARALLELISM, requests.active)
        assertEquals(ids.take(QUEUE_ITEM_PARALLELISM), requests.ids)

        requests.complete(ids.first())
        runCurrent()
        assertEquals(ids, requests.ids)
        assertEquals(ids.first(), current.await().id)
        for (id in ids.drop(1).asReversed()) requests.complete(id)
        first.await()
        second.await()
        loader.loadAll(ids, requests::fetch)
        assertEquals(ids, requests.ids)
        assertEquals(ids, ids.map { registry.get(it)?.id })
        assertEquals(QUEUE_ITEM_PARALLELISM, requests.peak)
    }

    @Test
    fun `failed request is shared but not cached and can be retried`() = runTest {
        val registry = QueueRegistry()
        val loader = QueueItemLoader(backgroundScope, registry)
        val requests = Requests()
        val cause = IOException("try again")
        val first = async { runCatching { loader.load("a") { requests.fetch("a") } } }
        val second = async { runCatching { loader.load("a") { requests.fetch("a") } } }
        runCurrent()
        assertEquals(listOf("a"), requests.ids)
        requests.fail("a", cause)
        assertFailure(cause, first.await())
        assertFailure(cause, second.await())
        assertNull(registry.get("a"))

        val retry = async { loader.load("a") { requests.fetch("a") } }
        runCurrent()
        assertEquals(listOf("a", "a"), requests.ids)
        requests.complete("a")
        assertSame(retry.await(), registry.get("a"))
    }

    @Test
    fun `cancelling one waiter does not cancel the other`() = runTest {
        val loader = QueueItemLoader(backgroundScope, QueueRegistry())
        val requests = Requests()
        val first = async { loader.load("a") { requests.fetch("a") } }
        val second = async { loader.load("a") { requests.fetch("a") } }
        runCurrent()
        first.cancelAndJoin()
        assertFalse(second.isCompleted)
        assertEquals(listOf("a"), requests.ids)
        requests.complete("a")
        assertEquals("a", second.await().id)
    }

    private fun assertFailure(expected: IOException, result: Result<*>) {
        val failure = result.exceptionOrNull()
        assertEquals(expected.javaClass, failure?.javaClass)
        assertEquals(expected.message, failure?.message)
    }
}
