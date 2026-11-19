package com.hans.aura.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class UpcomingOrderTest {
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
