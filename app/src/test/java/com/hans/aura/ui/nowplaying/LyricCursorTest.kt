package com.hans.aura.ui.nowplaying

import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.hans.aura.data.LyricBreak
import com.hans.aura.data.LyricsStore
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LyricCursorTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun onlyLineAndBreakChangesInvalidateTheCursorReader() {
        val position = mutableLongStateOf(0L)
        val offset = mutableLongStateOf(0L)
        val lines = LyricsStore.parseLrcText("[00:00]First\n[00:10]Second")
        val gap = LyricBreak(0, 5_000L, 10_000L)
        val positions = mutableListOf<LyricCursor>()
        compose.setContent {
            val cursor = rememberLyricCursor(lines, listOf(gap)) {
                (position.longValue + offset.longValue).coerceAtLeast(0L)
            }.value
            SideEffect { positions.add(cursor) }
        }

        compose.runOnIdle { position.longValue = 500L }
        compose.runOnIdle { assertEquals(listOf(LyricCursor(0, null)), positions) }
        compose.runOnIdle { position.longValue = 5_000L }
        compose.runOnIdle { assertEquals(LyricCursor(0, gap), positions.last()) }
        compose.runOnIdle { position.longValue = 5_500L }
        compose.runOnIdle {
            assertEquals(listOf(LyricCursor(0, null), LyricCursor(0, gap)), positions)
            offset.longValue = 4_500L
        }
        compose.runOnIdle { assertEquals(LyricCursor(1, null), positions.last()) }
        compose.runOnIdle { position.longValue = 0L }
        compose.runOnIdle {
            assertEquals(
                listOf(LyricCursor(0, null), LyricCursor(0, gap), LyricCursor(1, null), LyricCursor(0, null)),
                positions
            )
        }
    }
}
