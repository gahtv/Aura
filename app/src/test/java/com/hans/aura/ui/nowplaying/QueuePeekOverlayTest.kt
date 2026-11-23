package com.hans.aura.ui.nowplaying

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.ComposeUiTestConfig
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.hans.aura.data.Fixtures
import com.hans.aura.ui.theme.Accent
import com.hans.aura.ui.theme.AuraTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land")
class QueuePeekOverlayTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(
        ComposeUiTestConfig(inputMode = InputMode.Keyboard)
    )

    @Test
    fun rightFromFirstRowDoesNotFocusUnderlyingPause() {
        var collapses = 0
        val tracks = (0..2).map { Fixtures.track("Track $it") }
        compose.setContent {
            AuraTheme {
                Box(Modifier.fillMaxSize()) {
                    Button(
                        onClick = {},
                        modifier = Modifier.align(Alignment.CenterEnd).width(100.dp).fillMaxHeight()
                    ) { Text("Pause") }
                    QueuePeekOverlay(tracks, tracks.size, Accent, 1, 0L,
                        onExtend = {}, onCollapse = { collapses++ }, onJump = {})
                }
            }
        }
        compose.mainClock.advanceTimeBy(500)
        press(0, Key.DirectionRight)
        compose.onNodeWithText("Track 0").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, collapses) }
        compose.onNodeWithText("Pause").assertIsNotFocused()
        compose.onNodeWithText("Track 0").assertIsFocused()
    }

    @Test
    fun navigateExtendAndJumpToLowerRow() {
        var size by mutableStateOf(3)
        var jumped = -1
        var collapses = 0
        val tracks = (0..4).map { Fixtures.track("Track $it") }
        compose.setContent {
            AuraTheme {
                QueuePeekOverlay(tracks.take(size), tracks.size, Accent, 1, 0L,
                    onExtend = { size = 5 }, onCollapse = { collapses++ },
                    onJump = { jumped = it })
            }
        }
        compose.mainClock.advanceTimeBy(500)
        press(0, Key.DirectionDown)
        press(1, Key.DirectionDown)
        compose.runOnIdle { assertEquals(3, size) }
        press(2, Key.DirectionDown)
        press(3, Key.DirectionDown)
        press(4, Key.DirectionDown)
        press(4, Key.DirectionUp)
        press(3, Key.DirectionCenter)
        compose.runOnIdle { assertEquals(3, jumped); assertEquals(0, collapses) }
        press(3, Key.DirectionUp)
        press(2, Key.DirectionUp)
        press(1, Key.DirectionUp)
        press(0, Key.DirectionUp)
        compose.runOnIdle { assertEquals(1, collapses) }
    }

    private fun press(row: Int, key: Key) {
        compose.onNodeWithText("Track $row").assertIsFocused()
            .performKeyInput { pressKey(key) }
    }
}
