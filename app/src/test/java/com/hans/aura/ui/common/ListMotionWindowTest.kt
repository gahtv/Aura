package com.hans.aura.ui.common

import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test

class ListMotionWindowTest {
    private fun layout(total: Int, vararg indices: Int): LazyListLayoutInfo {
        val items = indices.map { index ->
            Proxy.newProxyInstance(
                LazyListItemInfo::class.java.classLoader,
                arrayOf(LazyListItemInfo::class.java)
            ) { _, method, _ ->
                when (method.name) {
                    "getIndex" -> index
                    else -> error("Unexpected item property: ${method.name}")
                }
            } as LazyListItemInfo
        }
        return Proxy.newProxyInstance(
            LazyListLayoutInfo::class.java.classLoader,
            arrayOf(LazyListLayoutInfo::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getVisibleItemsInfo" -> items
                "getTotalItemsCount" -> total
                else -> error("Unexpected layout property: ${method.name}")
            }
        } as LazyListLayoutInfo
    }

    @Test
    fun `visible shelves include two neighbors in both directions`() {
        assertEquals((2..8).toSet(), listMotionWindow(layout(12, 4, 5, 6)))
    }

    @Test
    fun `window clamps at both ends`() {
        assertEquals((0..3).toSet(), listMotionWindow(layout(4, 0, 1)))
        assertEquals((5..8).toSet(), listMotionWindow(layout(9, 7, 8)))
    }

    @Test
    fun `empty layout has no motion window`() {
        assertEquals(emptySet<Int>(), listMotionWindow(layout(0)))
        assertEquals(emptySet<Int>(), listMotionWindow(layout(12)))
    }

    @Test
    fun `negative lookahead is treated as zero`() {
        assertEquals(setOf(4, 5), listMotionWindow(layout(12, 4, 5), -1))
    }

    @Test
    fun `zero lookahead includes only visible items`() {
        assertEquals(setOf(4, 5), listMotionWindow(layout(12, 4, 5), 0))
    }

    @Test
    fun `large lookahead cannot overflow`() {
        assertEquals((0..11).toSet(), listMotionWindow(layout(12, 4, 5), Int.MAX_VALUE))
    }

    @Test
    fun `placement changes do not change the window until visible indices change`() {
        assertEquals(listMotionWindow(layout(12, 4, 5)), listMotionWindow(layout(12, 5, 4)))
        assertEquals((3..8).toSet(), listMotionWindow(layout(12, 5, 6)))
    }
}
