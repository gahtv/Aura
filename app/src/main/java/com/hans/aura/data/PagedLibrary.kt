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

    fun get(key: String): Page? = store[key]
    fun put(key: String, items: List<JellyfinItem>, total: Int) {
        store[key] = Page(items, total)
    }
    fun clear() = store.clear()
}
