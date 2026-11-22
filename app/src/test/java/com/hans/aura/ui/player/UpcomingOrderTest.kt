package com.hans.aura.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class UpcomingOrderTest {
    @Test fun restoredOrderKeepsSavedSurvivorsThenAddedEntries() {
        assertEquals(listOf("b", "a", "new2", "new1"), restoredUpcomingOrder(
            listOf("b", "gone", "a"), listOf("a", "new2", "b", "new1")))
    }

    @Test fun restoredOrderPreservesDuplicateSemantics() {
        assertEquals(listOf("b", "b", "a", "new", "new"), restoredUpcomingOrder(
            listOf("b", "b", "a", "gone"), listOf("a", "a", "new", "b", "new")))
    }

    @Test fun restoredOrderHandlesEmptyAndDisjointQueues() {
        assertEquals(emptyList<String>(), restoredUpcomingOrder(listOf("a"), emptyList()))
        assertEquals(listOf("b", "b"), restoredUpcomingOrder(emptyList(), listOf("b", "b")))
        assertEquals(emptyList<String>(), restoredUpcomingOrder(emptyList(), emptyList()))
        assertEquals(listOf("b"), restoredUpcomingOrder(listOf("a"), listOf("b")))
    }

    @Test fun restoredOrderMatchesListMembershipForRandomQueues() {
        val random = Random(48291)
        repeat(1000) {
            val saved = List(random.nextInt(100)) { "id${random.nextInt(20)}" }
            val live = List(random.nextInt(100)) { "id${random.nextInt(30)}" }
            assertEquals(saved.filter { it in live } + live.filter { it !in saved },
                restoredUpcomingOrder(saved, live))
        }
    }

    @Test fun fullQueueUsesShuffledHistoryAndPreservesTimelineIndices() {
        val order = listOf(3, 2, 0, 5, 1, 4)
        val result = queueIndices(6, 0,
            previous = { order.getOrElse(order.indexOf(it) - 1) { -1 } },
            next = { order.getOrElse(order.indexOf(it) + 1) { -1 } })
        assertEquals(order, result)
        assertEquals(5, result[3])
    }

    @Test fun fullQueueIsEmptyWithoutValidCurrent() {
        assertEquals(emptyList<Int>(), queueIndices(0, -1, { error("empty") }, { error("empty") }))
        assertEquals(emptyList<Int>(), queueIndices(3, 3, { error("invalid") }, { error("invalid") }))
    }

    @Test fun fullQueueHandlesCyclesAndRepeatAllWithoutDuplicates() {
        assertEquals(listOf(0, 1, 2), queueIndices(3, 1,
            previous = { if (it == 1) 0 else -1 }, next = { (it + 1) % 3 }))
        assertEquals(listOf(2, 0, 1), queueIndices(3, 1,
            previous = { (it + 2) % 3 }, next = { (it + 1) % 3 }))
        assertEquals(listOf(1), queueIndices(3, 1, { 9 }, { it }))
    }

    @Test fun fullSequentialQueueIncludesHistoryCurrentAndUpcoming() {
        assertEquals(listOf(0, 1, 2, 3), queueIndices(4, 2,
            previous = { it - 1 }, next = { if (it < 3) it + 1 else -1 }))
    }

    @Test fun shuffledSuccessorsMatchNext() {
        val successors = listOf(3, 4, -1, 1, 2)
        assertEquals(listOf(3, 1, 4, 2), upcomingIndices(5, 0) { successors[it] })
    }

    @Test fun emptyAndInvalidCurrentHaveNoSuccessors() {
        assertEquals(emptyList<Int>(), upcomingIndices(0, -1) { error("empty") })
        assertEquals(emptyList<Int>(), upcomingIndices(3, -1) { error("invalid") })
    }

    @Test fun repeatAllStopsBeforeRevisitingCurrent() {
        assertEquals(listOf(0, 1), upcomingIndices(3, 2) { (it + 1) % 3 })
    }

    @Test fun repeatOneOrMalformedCycleCannotLoopForever() {
        assertEquals(emptyList<Int>(), upcomingIndices(3, 1) { it })
        assertEquals(listOf(1), upcomingIndices(3, 0) { 1 })
        assertEquals(emptyList<Int>(), upcomingIndices(3, 0) { 9 })
    }

    @Test fun sequentialOrderStopsAtEnd() {
        assertEquals(listOf(2, 3), upcomingIndices(4, 1) { if (it < 3) it + 1 else -1 })
        assertEquals(emptyList<Int>(), upcomingIndices(4, 3) { -1 })
    }
}
