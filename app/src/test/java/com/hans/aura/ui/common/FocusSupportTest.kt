@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.hans.aura.ui.common

import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.hans.aura.ui.theme.ArtworkSeedCache
import com.hans.aura.ui.theme.AuraDynamicTheme
import com.hans.aura.ui.theme.AuraTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land")
class FocusSupportTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun artworkThemeChangesDoNotRepeatEntryFocus() = themeChangesKeepFocus(false)

    @Test
    fun artworkThemeChangesDoNotRepeatBackFocus() = themeChangesKeepFocus(true)

    private fun themeChangesKeepFocus(back: Boolean) {
        compose.mainClock.autoAdvance = false
        val artwork = mutableStateOf<String?>("focus-theme-red")
        ArtworkSeedCache.put("focus-theme-red", 0xFFDD2222.toInt())
        ArtworkSeedCache.put("focus-theme-blue", 0xFF2244DD.toInt())
        try {
            compose.setContent {
                AuraTheme {
                    AuraDynamicTheme(artwork.value) {
                        val first = remember { FocusRequester() }
                        Column(Modifier.background(androidx.tv.material3.MaterialTheme.colorScheme.surface)) {
                            Box(Modifier.size(40.dp).testTag("first").focusRequester(first).focusable())
                            Box(Modifier.size(40.dp).testTag("second").focusable())
                        }
                        if (back) RestoreFocusOnBack(1, first) else InitialFocusOnPush(true, first)
                    }
                }
            }
            repeat(6) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
            compose.onNodeWithTag("first").assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
            compose.onNodeWithTag("second").assertIsFocused()
            for (url in listOf("focus-theme-blue", "file:///nonexistent-focus-artwork.jpg", null)) {
                compose.runOnIdle { artwork.value = url }
                compose.mainClock.advanceTimeBy(600)
                compose.waitForIdle()
                compose.onNodeWithTag("second").assertIsFocused()
            }
        } finally {
            ArtworkSeedCache.clear()
        }
    }

    @Test
    fun retriesUntilTargetAttachesOnLaterFrames() {
        compose.mainClock.autoAdvance = false
        val requester = FocusRequester()
        val attached = mutableStateOf(false)
        var result: Boolean? = null
        compose.setContent {
            LaunchedEffect(Unit) { result = requester.requestFocusOnFrame() }
            LaunchedEffect(Unit) {
                repeat(3) { withFrameNanos { } }
                attached.value = true
            }
            if (attached.value) {
                Box(Modifier.size(40.dp).testTag("target").focusRequester(requester).focusable())
            }
        }
        compose.runOnIdle { assertNull(result) }
        repeat(6) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.runOnIdle { assertEquals(true, result) }
        compose.onNodeWithTag("target").assertIsFocused()
    }

    @Test
    fun unattachedTargetExhaustsExactlyTheFrameBudget() {
        compose.mainClock.autoAdvance = false
        val requester = FocusRequester()
        var result: Boolean? = null
        compose.setContent {
            LaunchedEffect(Unit) { result = requester.requestFocusOnFrame(maxFrames = 3) }
        }
        compose.runOnIdle { assertNull(result) }
        repeat(2) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.runOnIdle { assertNull(result) }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertEquals(false, result) }
    }

    @Test
    fun alreadyAttachedTargetNeedsNoFrame() {
        compose.mainClock.autoAdvance = false
        val requester = FocusRequester()
        var result: Boolean? = null
        compose.setContent {
            Box(Modifier.size(40.dp).testTag("target").focusRequester(requester).focusable())
            LaunchedEffect(Unit) { result = requester.requestFocusOnFrame(maxFrames = 0) }
        }
        compose.runOnIdle { assertEquals(true, result) }
        compose.onNodeWithTag("target").assertIsFocused()
    }

    @Test
    fun backRestoresKeeperInsteadOfFirstContainerChild() = restoreBack(true)

    @Test
    fun backFallsBackToContainerWhenKeeperItemIsMissing() = restoreBack(false)

    @Test
    fun completedEmptyEntryDoesNotFocusLaterRefresh() {
        compose.mainClock.autoAdvance = false
        val outside = FocusRequester()
        val content = FocusRequester()
        val ready = mutableStateOf(false)
        compose.setContent {
            Column {
                Box(Modifier.size(40.dp).testTag("outside").focusRequester(outside).focusable())
                if (ready.value) Box(Modifier.size(40.dp).testTag("later").focusRequester(content).focusable())
            }
            LaunchedEffect(Unit) { outside.requestFocus() }
            InitialFocusOnPush(true, content, ready = ready.value, cancelled = !ready.value)
        }
        repeat(4) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.runOnIdle { ready.value = true }
        repeat(4) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.onNodeWithTag("outside").assertIsFocused()
    }

    @Test
    fun completedEmptyBackDoesNotFocusLaterRefresh() {
        compose.mainClock.autoAdvance = false
        val outside = FocusRequester()
        val content = FocusRequester()
        val ready = mutableStateOf(false)
        compose.setContent {
            Column {
                Box(Modifier.size(40.dp).testTag("outside").focusRequester(outside).focusable())
                if (ready.value) Box(Modifier.size(40.dp).testTag("later").focusRequester(content).focusable())
            }
            LaunchedEffect(Unit) { outside.requestFocus() }
            RestoreFocusOnBack(1, content, ready = ready.value, cancelled = !ready.value)
        }
        repeat(4) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.runOnIdle { ready.value = true }
        repeat(4) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.onNodeWithTag("outside").assertIsFocused()
    }

    @Test
    fun navigationCancelsWaitingHandoffButNewPickCanFocus() {
        compose.mainClock.autoAdvance = false
        val first = FocusRequester()
        val content = FocusRequester()
        val ready = mutableStateOf(false)
        val pick = mutableStateOf(0)
        compose.setContent {
            FocusHandoffHost {
                Column {
                    Box(Modifier.size(40.dp).testTag("first").focusRequester(first).focusable())
                    Box(Modifier.size(40.dp).testTag("second").focusable())
                    if (ready.value) Box(Modifier.size(40.dp).testTag("loaded").focusRequester(content).focusable())
                }
                LaunchedEffect(Unit) { first.requestFocus() }
                key(pick.value) { InitialFocusOnPush(true, content, ready = ready.value) }
            }
        }
        repeat(4) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.onNodeWithTag("first").performKeyInput { pressKey(Key.DirectionDown) }
        repeat(4) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.onNodeWithTag("second").assertIsFocused()
        compose.runOnIdle { ready.value = true }
        repeat(4) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.onNodeWithTag("second").assertIsFocused()
        compose.runOnIdle { pick.value++ }
        repeat(4) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.onNodeWithTag("loaded").assertIsFocused()
    }

    @Test
    fun entryWaitsForChildOfAttachedGroupAfterDelayedLoad() = delayedEntry(true)

    @Test
    fun entryWaitsForGridAttachedAfterDelayedLoad() = delayedEntry(false)

    private fun delayedEntry(groupAttached: Boolean) {
        compose.mainClock.autoAdvance = false
        val content = FocusRequester()
        val outside = FocusRequester()
        val ready = mutableStateOf(false)
        compose.setContent {
            Column {
                Box(Modifier.size(40.dp).testTag("outside").focusRequester(outside).focusable())
                if (groupAttached || ready.value) {
                    Column(Modifier.focusRequester(content)) {
                        if (ready.value) Box(Modifier.size(40.dp).testTag("loaded").focusable())
                    }
                }
            }
            LaunchedEffect(Unit) { outside.requestFocus() }
            InitialFocusOnPush(true, content, ready = ready.value)
        }
        repeat(20) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.onNodeWithTag("outside").assertIsFocused()
        compose.runOnIdle { ready.value = true }
        repeat(4) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.onNodeWithTag("loaded").assertIsFocused()
    }

    @Test
    fun refreshDoesNotReclaimEntryFocusAfterUserMovesAway() {
        compose.mainClock.autoAdvance = false
        val content = FocusRequester()
        val outside = FocusRequester()
        val ready = mutableStateOf(true)
        compose.setContent {
            Column {
                Box(Modifier.size(40.dp).testTag("outside").focusRequester(outside).focusable())
                if (ready.value) Box(Modifier.size(40.dp).testTag("loaded").focusRequester(content).focusable())
            }
            InitialFocusOnPush(true, content, ready = ready.value)
        }
        repeat(4) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.onNodeWithTag("loaded").assertIsFocused()
        compose.runOnIdle { outside.requestFocus(); ready.value = false }
        repeat(4) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.runOnIdle { ready.value = true }
        repeat(4) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.onNodeWithTag("outside").assertIsFocused()
    }

    @Test
    fun backWaitsForContentAndDoesNotRepeatOnRefresh() {
        compose.mainClock.autoAdvance = false
        val content = FocusRequester()
        val outside = FocusRequester()
        val ready = mutableStateOf(false)
        compose.setContent {
            Column {
                Box(Modifier.size(40.dp).testTag("outside").focusRequester(outside).focusable())
                Column(Modifier.focusRequester(content)) {
                    if (ready.value) Box(Modifier.size(40.dp).testTag("loaded").focusable())
                }
            }
            LaunchedEffect(Unit) { outside.requestFocus() }
            RestoreFocusOnBack(1, content, ready = ready.value)
        }
        repeat(20) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.onNodeWithTag("outside").assertIsFocused()
        compose.runOnIdle { ready.value = true }
        repeat(4) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.onNodeWithTag("loaded").assertIsFocused()
        compose.runOnIdle { outside.requestFocus(); ready.value = false }
        repeat(4) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.runOnIdle { ready.value = true }
        repeat(4) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.onNodeWithTag("outside").assertIsFocused()
    }

    private fun restoreBack(hasKeeperItem: Boolean) {
        compose.mainClock.autoAdvance = false
        val container = FocusRequester()
        val keeper = FocusKeeper()
        val tick = mutableStateOf(0)
        val attached = mutableStateOf(false)
        compose.setContent {
            Column(Modifier.focusRequester(container)) {
                Box(Modifier.size(40.dp).testTag("fallback").focusable())
                if (attached.value) {
                    Box(Modifier.size(40.dp).testTag("saved")
                        .focusRequester(keeper.requesterFor("saved")).focusable())
                }
            }
            RestoreFocusOnBack(tick.value, container, "saved", keeper)
            LaunchedEffect(tick.value) {
                if (tick.value > 0 && hasKeeperItem) {
                    repeat(2) { withFrameNanos { } }
                    attached.value = true
                }
            }
        }
        compose.runOnIdle { tick.value++ }
        repeat(if (hasKeeperItem) 6 else 10) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        compose.onNodeWithTag(if (hasKeeperItem) "saved" else "fallback").assertIsFocused()
    }
}
