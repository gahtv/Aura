package com.hans.aura.ui.nowplaying

import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.ComposeUiTestConfig
import androidx.compose.ui.input.InputMode
import com.hans.aura.data.AdlibSegment
import com.hans.aura.data.LyricAdlib
import com.hans.aura.data.LyricBreak
import com.hans.aura.data.LyricLine
import com.hans.aura.data.LyricsStore
import com.hans.aura.data.lyricBreaks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LyricCursorTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(
        ComposeUiTestConfig(inputMode = InputMode.Keyboard)
    )

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

    @Test
    fun overlappingLinesTrackTheSungWindowWithoutExtraRecomposition() {
        val position = mutableLongStateOf(0L)
        // Lines 0 and 1 carry explicit ends that outlast their successors;
        // line 2 is the current line and has no end of its own.
        val lines = listOf(
            LyricLine(0L, "One", isSynced = true, endMs = 9_000L),
            LyricLine(2_000L, "Two", isSynced = true, endMs = 5_000L),
            LyricLine(4_000L, "Three", isSynced = true)
        )
        val cursors = mutableListOf<LyricCursor>()
        compose.setContent {
            val cursor = rememberLyricCursor(lines, emptyList()) {
                position.longValue.coerceAtLeast(0L)
            }.value
            SideEffect { cursors.add(cursor) }
        }

        // Before anything overlaps, the cursor is just the current line.
        compose.runOnIdle { position.longValue = 1_000L }
        compose.runOnIdle { assertEquals(listOf(LyricCursor(0, null)), cursors) }

        // Line 0 is still being sung while line 1 is current.
        compose.runOnIdle { position.longValue = 3_000L }
        compose.runOnIdle { assertEquals(LyricCursor(1, null, setOf(0)), cursors.last()) }

        // Line 2 (index 2) starts while BOTH earlier lines are still within their windows.
        compose.runOnIdle { position.longValue = 4_500L }
        compose.runOnIdle { assertEquals(LyricCursor(2, null, setOf(0, 1)), cursors.last()) }

        // An intermediate tick inside the overlap window changes nothing: the
        // reader is not invalidated and the pane does not recompose.
        compose.runOnIdle { position.longValue = 4_600L }
        compose.runOnIdle {
            assertEquals(3, cursors.size)
            assertEquals(LyricCursor(2, null, setOf(0, 1)), cursors.last())
        }

        // Each line clears as its own end passes; the current line is unmoved.
        compose.runOnIdle { position.longValue = 5_000L }
        compose.runOnIdle { assertEquals(LyricCursor(2, null, setOf(0)), cursors.last()) }
        compose.runOnIdle { position.longValue = 9_000L }
        compose.runOnIdle { assertEquals(LyricCursor(2, null, emptySet()), cursors.last()) }
    }

    @Test
    fun aBackingVocalCuedBeforeItsLeadFollowsTheNextLineWithoutActivatingEitherLead() {
        val position = mutableLongStateOf(0L)
        val early = LyricAdlib(listOf(AdlibSegment("ooh", 18_000L, 19_000L)), 19_000L)
        val lines = listOf(
            LyricLine(0L, "One", isSynced = true, endMs = 2_000L),
            LyricLine(20_000L, "Two", isSynced = true, adlib = early)
        )
        val breaks = lyricBreaks(lines)
        assertEquals(listOf(LyricBreak(0, 2_000L, 18_000L)), breaks)
        val cursors = mutableListOf<LyricCursor>()
        compose.setContent {
            val cursor = rememberLyricCursor(lines, breaks) { position.longValue }.value
            SideEffect { cursors.add(cursor) }
        }

        compose.runOnIdle { position.longValue = 10_000L }
        compose.runOnIdle { assertEquals(LyricCursor(0, breaks.single()), cursors.last()) }
        compose.runOnIdle { position.longValue = 18_500L }
        compose.runOnIdle { assertEquals(LyricCursor(0, null, emptySet(), pendingLine = 1), cursors.last()) }
        compose.runOnIdle { position.longValue = 20_000L }
        compose.runOnIdle { assertEquals(LyricCursor(1, null), cursors.last()) }
    }

    @Test
    fun anIntroBackingVocalMarksTheFirstLinePending() {
        val position = mutableLongStateOf(0L)
        val lines = listOf(
            LyricLine(12_000L, "One", isSynced = true,
                adlib = LyricAdlib(listOf(AdlibSegment("la", 11_600L)))),
            LyricLine(20_000L, "Two", isSynced = true)
        )
        val breaks = lyricBreaks(lines)
        val cursors = mutableListOf<LyricCursor>()
        compose.setContent {
            val cursor = rememberLyricCursor(lines, breaks) { position.longValue }.value
            SideEffect { cursors.add(cursor) }
        }

        compose.runOnIdle { position.longValue = 11_700L }
        compose.runOnIdle { assertEquals(LyricCursor(-1, null, emptySet(), pendingLine = 0), cursors.last()) }
        // An open-ended cue fills while its lead is pending.
        assertTrue(lines[0].adlib!!.karaokeFills(11_700L, leadIsCurrent = true).single().fraction > 0f)
    }
}
