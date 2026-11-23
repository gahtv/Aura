package com.hans.aura.ui.common

import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.hans.aura.ui.theme.AuraMotion
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DestinationViewportTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun outgoingAlbumRetainsItsScreenPositionWhenRailDisappears() =
        assertStableEntryViewport(initialNowPlaying = false)

    @Test
    fun outgoingNowPlayingRetainsItsScreenPositionWhenRailReturns() =
        assertStableEntryViewport(initialNowPlaying = true)

    private fun assertStableEntryViewport(initialNowPlaying: Boolean) {
        val nowPlaying = mutableStateOf(initialNowPlaying)
        val positions = mutableMapOf<Boolean, Float>()
        val states = mutableMapOf<Boolean, List<androidx.compose.foundation.lazy.LazyListState>>()
        compose.setContent {
            Box(Modifier.width(380.dp).height(200.dp)) {
                SharedTransitionLayout {
                    AnimatedContent(
                        targetState = nowPlaying.value,
                        modifier = Modifier.fillMaxSize(),
                        transitionSpec = {
                            fadeIn(AuraMotion.scheme.fastEffectsSpec()) togetherWith
                                fadeOut(AuraMotion.scheme.fastEffectsSpec())
                        },
                        label = "destination"
                    ) { entry ->
                        Box(Modifier.fillMaxSize().padding(start = if (entry) 0.dp else 80.dp)) {
                            DestinationFocusGate(entry == nowPlaying.value) {
                                val rows = List(2) { rememberLazyListState() }
                                states[entry] = rows
                                Column(Modifier.onGloballyPositioned { positions[entry] = it.positionInRoot().x }) {
                                    rows.forEach { state ->
                                        LazyRow(state = state, modifier = Modifier.height(80.dp)) {
                                            items(20) { Box(Modifier.width(90.dp).height(80.dp)) }
                                        }
                                        LaunchedEffect(Unit) { state.scrollToItem(19) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        var original = 0f
        var offsets = emptyList<Pair<Int, Int>>()
        compose.runOnIdle {
            original = positions.getValue(initialNowPlaying)
            offsets = states.getValue(initialNowPlaying).map { it.firstVisibleItemIndex to it.firstVisibleItemScrollOffset }
            nowPlaying.value = !initialNowPlaying
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(original, positions.getValue(initialNowPlaying), 0.01f)
            assertEquals(offsets, states.getValue(initialNowPlaying).map { it.firstVisibleItemIndex to it.firstVisibleItemScrollOffset })
        }
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        compose.runOnIdle { nowPlaying.value = initialNowPlaying }
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle {
            assertEquals(original, positions.getValue(initialNowPlaying), 0.01f)
            assertEquals(offsets, states.getValue(initialNowPlaying).map { it.firstVisibleItemIndex to it.firstVisibleItemScrollOffset })
        }
    }
}
