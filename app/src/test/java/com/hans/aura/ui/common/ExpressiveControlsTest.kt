package com.hans.aura.ui.common

import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTestConfig
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Density
import com.hans.aura.ui.theme.AuraTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land")
class ExpressiveControlsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(
        ComposeUiTestConfig(inputMode = InputMode.Keyboard)
    )

    @Test
    fun connectedActionsKeepIndividualDpadFocusAndActivation() {
        val first = FocusRequester()
        val clicked = mutableListOf<String>()
        compose.setContent {
            AuraTheme {
                ConnectedActions(
                    listOf(
                        ConnectedAction("Play", { clicked += "Play" }, Modifier.focusRequester(first)),
                        ConnectedAction("Shuffle", { clicked += "Shuffle" }),
                        ConnectedAction("Radio", { clicked += "Radio" })
                    )
                )
            }
        }
        compose.runOnIdle { first.requestFocus() }
        press("Play", Key.DirectionRight)
        press("Shuffle", Key.DirectionCenter)
        press("Shuffle", Key.DirectionRight)
        press("Radio", Key.DirectionCenter)
        press("Radio", Key.DirectionLeft)
        press("Shuffle", Key.DirectionLeft)
        press("Play", Key.DirectionCenter)
        compose.runOnIdle { assertEquals(listOf("Shuffle", "Radio", "Play"), clicked) }
    }

    @Test
    fun filterCornerInterpolatesWhenSelectedWithoutLosingFocus() {
        val selected = mutableStateOf(false)
        var radius = Float.NaN
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val shape = filterPillShape(selected.value, focused = true)
            SideEffect { radius = shape.topStart.toPx(Size(120f, 40f), Density(1f)) }
        }
        compose.runOnIdle {
            assertEquals(8f, radius, 0.01f)
            selected.value = true
        }
        val samples = List(15) {
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle { radius }
        }
        assertTrue("Expected an intermediate corner radius: $samples", samples.any { it in 4.01f..7.99f })
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle { assertEquals(4f, radius, 0.01f) }
    }

    private fun press(label: String, key: Key) {
        compose.onNodeWithText(label).assertIsFocused().performKeyInput { pressKey(key) }
    }
}
