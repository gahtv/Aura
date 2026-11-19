package com.hans.aura.ui.nowplaying

import com.hans.aura.data.LyricLine
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricPartsTest {
    private fun line(part: Int) = LyricLine(0L, "line", isSynced = true, part = part)

    @Test
    fun `only the last line before a new part ends it`() {
        val lines = listOf(line(1), line(1), line(2), line(3), line(3))
        assertEquals(listOf(false, true, true, false, false), lines.indices.map { endsSongPart(lines, it) })
    }

    @Test
    fun `lyrics without parts never break`() {
        val lines = listOf(line(0), line(0), line(0))
        assertEquals(listOf(false, false, false), lines.indices.map { endsSongPart(lines, it) })
    }
}
