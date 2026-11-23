package com.hans.aura.ui.common

import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsFocused
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SharedArtworkTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun missingScopesPreserveModifierIdentity() {
        val modifier = Modifier.size(40.dp)
        compose.setContent {
            assertSame(modifier, modifier.sharedArtwork("album"))
            SharedTransitionLayout {
                CompositionLocalProvider(LocalArtworkTransition provides this) {
                    assertSame(modifier, modifier.sharedArtwork("album"))
                }
            }
        }
    }

    @Test
    fun matchingAlbumIdsShareArtwork() = checkMatch("album", true)

    @Test
    fun differentAlbumIdsDoNotShareArtwork() = checkMatch("other", false)

    @Test
    fun emptyIdDoesNotMatchAnAlbum() = checkMatch("", false)

    @Test
    fun activeDestinationPreservesNonFocusableContentGroup() {
        compose.setContent {
            val requester = remember { FocusRequester() }
            Box(Modifier.focusRequester(requester).focusGroup()) {
                DestinationFocusGate(true) {
                    Column(Modifier.focusGroup()) {
                        Box(Modifier.size(40.dp).testTag("card").focusable())
                    }
                }
            }
            InitialFocusOnPush(true, requester)
        }
        compose.onNodeWithTag("card").assertIsFocused()
    }

    @Test
    fun outgoingDestinationCannotStealFocusWhenItsDataArrives() {
        val active = mutableStateOf(true)
        val ready = mutableStateOf(false)
        compose.setContent {
            Column {
                CompositionLocalProvider(LocalDestinationActive provides active.value) {
                    val requester = remember { FocusRequester() }
                    DestinationFocusGate(active.value) {
                        Box(Modifier.size(40.dp).testTag("outgoing").focusRequester(requester).focusable())
                    }
                    InitialFocusOnPush(true, requester, ready.value)
                    RestoreFocusOnBack(1, requester, ready = ready.value)
                }
                val incoming = remember { FocusRequester() }
                Box(Modifier.size(40.dp).testTag("incoming").focusRequester(incoming).focusable())
                InitialFocusOnPush(!active.value, incoming)
            }
        }
        compose.runOnIdle { active.value = false }
        compose.waitForIdle()
        compose.onNodeWithTag("incoming").assertIsFocused()
        compose.runOnIdle { ready.value = true }
        compose.waitForIdle()
        compose.onNodeWithTag("incoming").assertIsFocused()
    }

    private fun checkMatch(destinationKey: String, expected: Boolean) {
        compose.mainClock.autoAdvance = false
        val destination = mutableStateOf(false)
        var target: SharedTransitionScope.SharedContentState? = null
        compose.setContent {
            SharedTransitionLayout {
                AnimatedContent(destination.value, label = "test") { detail ->
                    CompositionLocalProvider(
                        LocalArtworkTransition provides this@SharedTransitionLayout,
                        LocalArtworkVisibility provides this@AnimatedContent
                    ) {
                        if (detail) {
                            val state = rememberSharedContentState(destinationKey)
                            target = state
                            Box(Modifier.size(120.dp).sharedElement(state, this@AnimatedContent))
                        } else {
                            Box(Modifier.size(40.dp).sharedArtwork("album"))
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { destination.value = true }
        repeat(3) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.runOnIdle {
            if (expected) assertTrue(target?.isMatchFound == true)
            else assertFalse(target?.isMatchFound == true)
        }
    }
}
