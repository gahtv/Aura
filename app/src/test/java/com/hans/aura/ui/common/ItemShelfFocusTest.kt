package com.hans.aura.ui.common

import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.hans.aura.data.JellyfinItem
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land")
class ItemShelfFocusTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun backBringsOffscreenShelfAndCardIntoCompositionAfterLoading() {
        compose.mainClock.autoAdvance = false
        val content = FocusRequester()
        val outside = FocusRequester()
        val keeper = FocusKeeper()
        val loaded = mutableStateOf(false)
        val prepared = mutableStateOf(false)
        val albums = List(24) { JellyfinItem(id = "album$it", name = "Album $it", type = "MusicAlbum") }
        compose.setContent {
            val list = rememberLazyListState()
            Column {
                Box(Modifier.size(30.dp).testTag("outside").focusRequester(outside).focusable())
                LazyColumn(Modifier.height(400.dp).focusRequester(content), state = list) {
                    items(30) { Box(Modifier.height(100.dp)) }
                    item {
                        ItemShelf(
                            title = "Lower shelf", items = if (loaded.value) albums else null,
                            serverUrl = "http://localhost", focusKeeper = keeper,
                            onFocused = {}, onOpen = { _, _ -> }, size = 80.dp,
                            restoreItemId = "album23".takeUnless { prepared.value },
                            onRestoreReady = { prepared.value = true }
                        )
                    }
                }
            }
            LaunchedEffect(Unit) { outside.requestFocus() }
            LaunchedEffect(loaded.value) { if (loaded.value) list.scrollToItem(30) }
            RestoreFocusOnBack(1, content, "album23", keeper, ready = prepared.value)
        }
        repeat(20) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.onNodeWithTag("outside").assertIsFocused()
        compose.runOnIdle { loaded.value = true }
        repeat(8) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.onNodeWithText("Album 23").assertIsFocused()
    }

    @Test
    fun absentCardDoesNotReportReady() {
        var prepared = false
        compose.setContent {
            ItemShelf(
                title = "Empty", items = emptyList(), serverUrl = "http://localhost",
                focusKeeper = FocusKeeper(), onFocused = {}, onOpen = { _, _ -> },
                restoreItemId = "missing", onRestoreReady = { prepared = true }
            )
        }
        compose.runOnIdle { assertFalse(prepared) }
    }
}
