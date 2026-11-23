package com.hans.aura.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.hans.aura.ui.common.FocusKeeper
import com.hans.aura.ui.common.RestoreFocusOnBack
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HomeShelfFocusTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun backFindsSavedShelfAfterRowsAreInsertedWhileAway() = restoreShelf(true)

    @Test
    fun backFindsSavedShelfAfterPlaceholderAboveGrowsWhileAway() = restoreShelf(false, growPlaceholder = true)

    @Test
    fun unchangedRowsKeepBothSavedOffsets() = restoreShelf(false)

    @Test
    fun nonCardExitPreservesSavedScroll() = restoreShelf(false, leaveThroughShell = true)

    @Test
    fun newHomeEntryCapturesFirstShelfBeforeShellExit() = restoreShelf(false, leaveThroughShell = true, newEntry = true)

    @Test
    fun vanishedShelfUsesContainerFallback() = restoreShelf(false, true)

    private fun restoreShelf(insertRows: Boolean, removeShelf: Boolean = false, leaveThroughShell: Boolean = false,
        newEntry: Boolean = false, growPlaceholder: Boolean = false) {
        compose.mainClock.autoAdvance = false
        val away = mutableStateOf(false)
        val inserted = mutableStateOf(false)
        val returned = mutableStateOf(false)
        val focusedShelf = mutableStateOf(if (newEntry) null else "savedShelf")
        var savedOffset = 0
        val shellFocus = FocusRequester()
        lateinit var outer: LazyListState
        lateinit var inner: LazyListState
        lateinit var keeper: FocusKeeper
        compose.setContent {
            val holder = rememberSaveableStateHolder()
            if (!away.value) holder.SaveableStateProvider("home") {
                val list = rememberLazyListState()
                val content = remember { FocusRequester() }
                keeper = remember { FocusKeeper() }
                outer = list
                val rows = buildList {
                    addAll(listOf("hero", "quickAccess"))
                    if (inserted.value) repeat(4) { add("history$it") }
                    if (!removeShelf || !returned.value) add("savedShelf")
                    add("bottom")
                }
                LazyColumn(Modifier.height(300.dp).width(320.dp).focusRequester(content), state = list) {
                    rows.forEach { row ->
                        item(key = row) {
                            if (row == "savedShelf") {
                                val horizontal = rememberLazyListState(20)
                                inner = horizontal
                                LazyRow(state = horizontal, modifier = Modifier.height(250.dp),
                                    verticalAlignment = Alignment.Bottom) {
                                    items(24, key = { "card$it" }) { index ->
                                        Box(Modifier.size(80.dp).testTag("card$index")
                                            .focusRequester(keeper.requesterFor("card$index")).focusable())
                                    }
                                }
                            } else {
                                val height = if (growPlaceholder && row == "quickAccess") {
                                    if (returned.value) 650.dp else 80.dp
                                } else 250.dp
                                Box(Modifier.height(height).width(320.dp).testTag(row).focusable())
                            }
                        }
                    }
                }
                val tick = (if (newEntry) 1 else 0) + if (returned.value) 1 else 0
                val missing = removeShelf && returned.value
                val ready = homeShelfReady(tick, list, rows, focusedShelf.value, !missing)
                RestoreFocusOnBack(tick, content, "card23", keeper, ready = ready || missing)
            }
            Box(Modifier.size(40.dp).testTag("nowPlaying").focusRequester(shellFocus).focusable())
        }
        compose.runOnIdle { runBlocking { outer.scrollToItem(if (growPlaceholder) 1 else 2, if (growPlaceholder) 60 else 37) } }
        frames()
        compose.runOnIdle { runBlocking { inner.scrollToItem(20, 13) } }
        frames()
        compose.runOnIdle { keeper.requesterFor("card23").requestFocus(); focusedShelf.value = "savedShelf" }
        frames()
        compose.onNodeWithTag("card23").assertIsFocused()
        compose.runOnIdle { runBlocking { outer.scrollBy(17f) } }
        frames()
        val horizontalBefore = compose.runOnIdle { inner.firstVisibleItemIndex to inner.firstVisibleItemScrollOffset }
        val verticalBefore = compose.runOnIdle { outer.firstVisibleItemIndex to outer.firstVisibleItemScrollOffset }
        org.junit.Assert.assertEquals(if (growPlaceholder) 1 to 77 else 2 to 54, verticalBefore)
        compose.runOnIdle {
            val shelf = outer.layoutInfo.visibleItemsInfo.first { it.key == "savedShelf" }
            savedOffset = shelf.offset
        }
        if (leaveThroughShell) {
            compose.runOnIdle { shellFocus.requestFocus() }
            frames()
            compose.onNodeWithTag("nowPlaying").assertIsFocused()
        }
        compose.runOnIdle { away.value = true }
        frames()
        compose.runOnIdle { inserted.value = insertRows; returned.value = true; away.value = false }
        frames(12)
        if (removeShelf) {
            compose.onAllNodes(isFocused()).assertCountEquals(1)
            return
        }
        compose.onNodeWithTag("card23").assertIsFocused()
        compose.runOnIdle {
            org.junit.Assert.assertEquals(horizontalBefore, inner.firstVisibleItemIndex to inner.firstVisibleItemScrollOffset)
            org.junit.Assert.assertEquals(savedOffset, outer.layoutInfo.visibleItemsInfo.first { it.key == "savedShelf" }.offset)
            if (growPlaceholder) org.junit.Assert.assertEquals(2,
                outer.layoutInfo.visibleItemsInfo.first { it.key == "savedShelf" }.index)
            if (!insertRows && !growPlaceholder) org.junit.Assert.assertEquals(verticalBefore,
                outer.firstVisibleItemIndex to outer.firstVisibleItemScrollOffset)
        }
    }

    private fun frames(count: Int = 6) {
        repeat(count) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
    }
}
