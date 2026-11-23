package com.hans.aura.ui.common

import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
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
            InitialFocusOnPush(true, content)
        }
        repeat(20) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
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
            InitialFocusOnPush(ready.value, content)
        }
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
