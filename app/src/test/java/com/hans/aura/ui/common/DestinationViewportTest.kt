package com.hans.aura.ui.common

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DestinationViewportTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun inactiveDestinationRetainsEndOfShelfOffsetsWhenRailDisappears() {
        val active = mutableStateOf(true)
        val offsets = mutableListOf<Pair<Int, Int>>()
        val states = mutableListOf<androidx.compose.foundation.lazy.LazyListState>()
        compose.setContent {
            Box(Modifier.width(if (active.value) 300.dp else 380.dp).height(200.dp)) {
                DestinationFocusGate(active.value) {
                    Column {
                        repeat(2) { row ->
                            val state = rememberLazyListState()
                            if (states.size <= row) states.add(state)
                            LazyRow(state = state, modifier = Modifier.height(80.dp)) {
                                items(20) { Box(Modifier.width(90.dp).height(80.dp)) }
                            }
                            LaunchedEffect(Unit) { state.scrollToItem(19) }
                        }
                    }
                }
            }
        }
        compose.runOnIdle {
            states.forEach { offsets.add(it.firstVisibleItemIndex to it.firstVisibleItemScrollOffset) }
            active.value = false
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(offsets, states.map { it.firstVisibleItemIndex to it.firstVisibleItemScrollOffset })
            active.value = true
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(offsets, states.map { it.firstVisibleItemIndex to it.firstVisibleItemScrollOffset })
        }
    }
}
