package com.hans.aura.data

import java.util.concurrent.ConcurrentHashMap

/**
 * Library query cache with a 24h TTL, keyed by stable request keys.
 * Kills the per-navigation refetch lag; expired data is refetched on next use.
 * Manual refresh (Settings > Refresh library) clears everything.
 */
object LibraryCache {
    private const val TTL_MS = 24L * 60 * 60 * 1000
    private data class Entry(val savedAtMs: Long, val items: List<JellyfinItem>)
    private val store = ConcurrentHashMap<String, Entry>()

    fun get(key: String): List<JellyfinItem>? =
        store[key]?.takeIf { System.currentTimeMillis() - it.savedAtMs < TTL_MS }?.items

    fun put(key: String, items: List<JellyfinItem>) {
        store[key] = Entry(System.currentTimeMillis(), items)
    }

    fun evict(key: String) { store.remove(key) }
    fun clear() = store.clear()
}
