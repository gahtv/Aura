package com.hans.aura.ui.library

import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.hans.aura.data.JellyfinItem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LibraryGridFocusTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun emptyLetterPickDoesNotFocusLaterRefresh() = handoff(emptyResult = true)

    @Test
    fun changedQueryCancelsLoadingLetterPick() = handoff(emptyResult = false)

    private fun handoff(emptyResult: Boolean) {
        compose.mainClock.autoAdvance = false
        val sort = FocusRequester()
        val grid = FocusRequester()
        val pick = mutableStateOf(1 to "old")
        val state = mutableStateOf(LibraryUiState(
            initialized = true, loading = !emptyResult, jumpTick = 1, cacheKey = "old"
        ))
        compose.setContent {
            Column {
                Box(Modifier.size(40.dp).testTag("sort").focusRequester(sort).focusable())
                if (state.value.items.isNotEmpty()) {
                    Box(Modifier.size(40.dp).testTag("grid").focusRequester(grid).focusable())
                }
            }
            LaunchedEffect(Unit) { sort.requestFocus() }
            LibraryGridFocus(pick.value, state.value, grid)
        }
        frames()
        compose.runOnIdle {
            state.value = state.value.copy(
                loading = false, cacheKey = if (emptyResult) "old" else "other",
                items = listOf(JellyfinItem(id = "album", name = "Album"))
            )
        }
        frames()
        compose.onNodeWithTag("sort").assertIsFocused()
        compose.runOnIdle {
            state.value = state.value.copy(jumpTick = 2)
            pick.value = 2 to state.value.cacheKey
        }
        frames()
        compose.onNodeWithTag("grid").assertIsFocused()
    }

    private fun frames() {
        repeat(4) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
    }
}
