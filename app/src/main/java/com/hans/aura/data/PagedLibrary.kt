package com.hans.aura.data

import java.util.concurrent.ConcurrentHashMap

/**
 * Session-scoped store for paginated library lists. Keeps the accumulated
 * pages + total alive while the user navigates away and back, so scroll
 * position and focus restoration work across full library depths.
 */
object PagedLibrary {
    data class Page(val items: List<JellyfinItem>, val total: Int)

    private val store = ConcurrentHashMap<String, Page>()

    fun get(key: String): Page? = store[k(key)]
    fun put(key: String, items: List<JellyfinItem>, total: Int) {
        store[k(key)] = Page(items, total)
    }
    val size: Int get() = store.size
    fun clear() = store.clear()

    /** Same family eviction as LibraryCache.evictWhere, for the paged store. */
    fun evictWhere(predicate: (String) -> Boolean) {
        store.keys.filter(predicate).forEach { store.remove(it) }
    }

    /** Paged lists are scoped to the library selection, same as LibraryCache. */
    private fun k(key: String): String = key + LibraryFilter.cacheSuffix()
}
