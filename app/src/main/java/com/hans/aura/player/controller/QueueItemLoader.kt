package com.hans.aura.player.controller

import com.hans.aura.data.JellyfinItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal const val QUEUE_ITEM_PARALLELISM = 6

internal suspend fun fetchQueueItems(
    ids: List<String>,
    permits: Semaphore,
    fetch: suspend (String) -> JellyfinItem
): List<JellyfinItem> = awaitQueueItems(ids) { id -> permits.withPermit { fetch(id) } }

private suspend fun awaitQueueItems(
    ids: List<String>,
    fetch: suspend (String) -> JellyfinItem
): List<JellyfinItem> = coroutineScope {
    ids.map { id -> async { fetch(id) } }.awaitAll()
}

internal class QueueItemLoader(
    private val scope: CoroutineScope,
    private val registry: QueueRegistry
) {
    private val permits = Semaphore(QUEUE_ITEM_PARALLELISM)
    private val lock = Any()
    private val inFlight = mutableMapOf<String, Deferred<Result<JellyfinItem>>>()

    suspend fun load(id: String, fetch: suspend () -> JellyfinItem): JellyfinItem {
        val request = synchronized(lock) {
            registry.get(id)?.let { return it }
            inFlight[id]?.takeUnless { it.isCompleted } ?: startRequest(id, fetch)
        }
        return request.await().getOrThrow()
    }

    private fun startRequest(
        id: String,
        fetch: suspend () -> JellyfinItem
    ): Deferred<Result<JellyfinItem>> {
        val request = scope.async(start = CoroutineStart.LAZY) {
            try {
                Result.success(permits.withPermit { registry.get(id) ?: fetch().also(registry::put) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
        inFlight[id] = request
        request.invokeOnCompletion {
            synchronized(lock) {
                if (inFlight[id] === request) inFlight.remove(id)
            }
        }
        return request
    }

    suspend fun loadAll(ids: List<String>, fetch: suspend (String) -> JellyfinItem) {
        awaitQueueItems(ids) { id -> load(id) { fetch(id) } }
    }
}
