package com.hans.aura.ui.common

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decoder budget on its own, with no Android in sight: three slots, evict
 * the oldest on the fourth, and a release that actually frees one.
 */
class MotionSchedulerTest {

    private fun scheduler(capacity: Int = MotionScheduler.DEFAULT_CAPACITY) =
        MotionScheduler(capacity)

    @Test
    fun `capacity is three by default`() {
        assertEquals(3, MotionScheduler.DEFAULT_CAPACITY)
        assertEquals(3, MotionBudget.capacity)
    }

    @Test
    fun `three cards animate and the fourth evicts the oldest`() = runBlocking {
        val budget = scheduler()
        listOf("a", "b", "c").forEach { assertTrue(it, budget.acquire(it)) }
        assertEquals(setOf("a", "b", "c"), budget.active.value)

        // Fourth asks: it gets a slot, and the oldest holder (a) loses its own.
        assertTrue(budget.acquire("d"))
        assertEquals(setOf("b", "c", "d"), budget.active.value)
    }

    @Test
    fun `release frees a slot for the next card`() = runBlocking {
        val budget = scheduler()
        listOf("a", "b", "c").forEach { budget.acquire(it) }
        budget.release("b")
        assertEquals(setOf("a", "c"), budget.active.value)

        assertTrue(budget.acquire("d"))
        assertEquals(setOf("a", "c", "d"), budget.active.value)
        // Nothing was evicted to make room.
        assertTrue("a" in budget.active.value)
        assertTrue("c" in budget.active.value)
    }

    @Test
    fun `release of a stranger is a no-op`() = runBlocking {
        val budget = scheduler()
        budget.acquire("a")
        budget.release("never-asked")
        assertEquals(setOf("a"), budget.active.value)
    }

    @Test
    fun `the same id is only given one slot`() = runBlocking {
        val budget = scheduler()
        assertTrue(budget.acquire("album"))
        // A duplicate of the same cover (two shelves, same album) must not build
        // a second decoder for it.
        assertFalse(budget.acquire("album"))
        assertEquals(setOf("album"), budget.active.value)
    }

    @Test
    fun `a prefetch card does not evict an on-screen one`() = runBlocking {
        val budget = scheduler()
        // Visible rows carry a higher priority than the two lookahead rows.
        listOf("visible1", "visible2", "visible3").forEach {
            assertTrue(budget.acquire(it, priority = 10))
        }
        assertFalse(budget.acquire("below-the-fold", priority = 0))
        assertEquals(setOf("visible1", "visible2", "visible3"), budget.active.value)
    }

    @Test
    fun `a higher priority card evicts the oldest holder`() = runBlocking {
        val budget = scheduler()
        listOf("a", "b", "c").forEach { budget.acquire(it, priority = 0) }
        assertTrue(budget.acquire("near", priority = 10))
        assertEquals(setOf("b", "c", "near"), budget.active.value)
    }

    @Test
    fun `reset empties the budget`() = runBlocking {
        val budget = scheduler()
        listOf("a", "b", "c").forEach { budget.acquire(it) }
        budget.reset()
        assertTrue(budget.active.value.isEmpty())
        assertTrue(budget.acquire("d"))
    }
}
