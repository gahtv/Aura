package com.hans.aura.ui.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Test

class QueuePeekIndexTest {
    private val queue = (1..6).map { "Track $it" }

    @Test
    fun `peek row maps to the queue entry it shows, not to the top of the queue`() {
        // Track 2 is playing, so the peek lists Track 3, 4, 5.
        val playing = 1
        val shown = queue.drop(playing + 1).take(3)
        shown.forEachIndexed { row, title ->
            assertEquals(title, queue[peekQueueIndex(playing, row)])
        }
    }

    @Test
    fun `first row is the next track from the start of the queue`() {
        assertEquals(1, peekQueueIndex(queueIndex = 0, row = 0))
    }

    @Test
    fun `unknown playing index lists the queue from its start`() {
        assertEquals(0, peekQueueIndex(queueIndex = -1, row = 0))
        assertEquals(2, peekQueueIndex(queueIndex = -1, row = 2))
    }
}
