package com.hans.aura.ui.common

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide budget for motion-artwork decoders.
 *
 * A TV box can decode a handful of 1080p H.264 loops; a browse grid of forty
 * covers cannot. This hands out at most [capacity] slots and, when a further
 * card asks, **evicts** rather than queues — the dropped id leaves [active],
 * which is what tells that card to tear its player down and fall back to the
 * still poster.
 *
 * Eviction order is least-recently-acquired first, except that a caller may
 * pass a higher [acquire] `priority` to protect an on-screen card from a
 * prefetch one: a lower-priority caller that finds every slot taken by
 * higher-priority holders is refused (`false`) instead of stealing a slot.
 *
 * One id, one slot: asking twice for an id that is already animating is
 * refused, so a card duplicated across two home shelves cannot end up with two
 * decoders on the same cover.
 *
 * Deliberately Android-free so the policy can be unit-tested on the JVM.
 */
class MotionScheduler(val capacity: Int = DEFAULT_CAPACITY) {

    private val lock = Any()
    // id -> acquire stamp. LinkedHashMap keeps it in acquisition order for the
    // victim scan (the oldest entry is the first candidate).
    private val holds = LinkedHashMap<String, Long>()
    private val priorities = HashMap<String, Int>()
    private var clock = 0L

    private val _active = MutableStateFlow<Set<String>>(emptySet())

    /** Ids currently allowed to animate. */
    val active: StateFlow<Set<String>> = _active.asStateFlow()

    /**
     * Asks for a slot for [id]. True means "you may build and start a player";
     * false means "draw the still poster and ask again when you move".
     *
     * Never suspends in practice (the budget is in-memory); suspending lets
     * callers acquire from a LaunchedEffect without blocking composition.
     */
    suspend fun acquire(id: String, priority: Int = 0): Boolean = admit(id, priority)

    /** Gives [id]'s slot back. Safe to call for an id that never got one. */
    fun release(id: String) {
        synchronized(lock) {
            if (holds.remove(id) != null) {
                priorities.remove(id)
                publish()
            }
        }
    }

    /** Drops every holder. Tests only. */
    fun reset() {
        synchronized(lock) {
            holds.clear()
            priorities.clear()
            clock = 0L
            publish()
        }
    }

    private fun admit(id: String, priority: Int): Boolean = synchronized(lock) {
        if (id.isBlank() || capacity <= 0) return false
        // Already animating under another composable: keep the one player.
        if (holds.containsKey(id)) return false

        if (holds.size >= capacity) {
            val victim = holds.entries.minWithOrNull(
                compareBy({ priorities[it.key] ?: 0 }, { it.value })
            ) ?: return false
            // A prefetch card (lower priority) must never push an on-screen one
            // out of its slot: refuse it and let it stay a poster.
            if (priority < (priorities[victim.key] ?: 0)) return false
            holds.remove(victim.key)
            priorities.remove(victim.key)
        }

        holds[id] = ++clock
        priorities[id] = priority
        publish()
        true
    }

    private fun publish() {
        val next: Set<String> = holds.keys.toSet()
        if (next != _active.value) _active.value = next
    }

    companion object {
        const val DEFAULT_CAPACITY = 3
    }
}

/** The app-wide instance: three motion decoders, no matter how many screens ask. */
object MotionBudget {

    val capacity: Int = MotionScheduler.DEFAULT_CAPACITY

    private val scheduler = MotionScheduler(capacity)

    val active: StateFlow<Set<String>> get() = scheduler.active

    suspend fun acquire(id: String, priority: Int = 0): Boolean =
        scheduler.acquire(id, priority)

    fun release(id: String) = scheduler.release(id)

    /** Tests only. */
    fun reset() = scheduler.reset()
}
