package com.hans.aura.ui.common

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.ComposeUiTestConfig
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.hans.aura.AuraApp
import com.hans.aura.data.Fixtures
import com.hans.aura.ui.login.LoginScreen
import com.hans.aura.ui.nowplaying.QueuePeekOverlay
import com.hans.aura.ui.theme.AuraTheme
import com.hans.aura.ui.theme.AuraColors
import org.junit.Rule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land")
class PanelFocusTrapTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(
        ComposeUiTestConfig(inputMode = InputMode.Keyboard)
    )

    private enum class Panel { Bio, Confirm, Context, Login, Queue }

    @Test fun queueTrapsRight() = assertTrapped(Panel.Queue, Key.DirectionRight)
    @Test fun queueTrapsLeft() = assertTrapped(Panel.Queue, Key.DirectionLeft)
    @Test fun queueTrapsUp() = assertTrapped(Panel.Queue, Key.DirectionUp)
    @Test fun queueTrapsDown() = assertTrapped(Panel.Queue, Key.DirectionDown)

    @Test fun bioTrapsRight() = assertTrapped(Panel.Bio, Key.DirectionRight)
    @Test fun bioTrapsLeft() = assertTrapped(Panel.Bio, Key.DirectionLeft)
    @Test fun bioTrapsUp() = assertTrapped(Panel.Bio, Key.DirectionUp)
    @Test fun bioTrapsDown() = assertTrapped(Panel.Bio, Key.DirectionDown)
    @Test fun confirmTrapsRight() = assertTrapped(Panel.Confirm, Key.DirectionRight)
    @Test fun confirmTrapsLeft() = assertTrapped(Panel.Confirm, Key.DirectionLeft)
    @Test fun confirmTrapsUp() = assertTrapped(Panel.Confirm, Key.DirectionUp)
    @Test fun confirmTrapsDown() = assertTrapped(Panel.Confirm, Key.DirectionDown)
    @Test fun contextTrapsRight() = assertTrapped(Panel.Context, Key.DirectionRight)
    @Test fun contextTrapsLeft() = assertTrapped(Panel.Context, Key.DirectionLeft)
    @Test fun contextTrapsUp() = assertTrapped(Panel.Context, Key.DirectionUp)
    @Test fun contextTrapsDown() = assertTrapped(Panel.Context, Key.DirectionDown)
    @Test fun loginTrapsRight() = assertTrapped(Panel.Login, Key.DirectionRight)
    @Test fun loginTrapsLeft() = assertTrapped(Panel.Login, Key.DirectionLeft)
    @Test fun loginTrapsUp() = assertTrapped(Panel.Login, Key.DirectionUp)
    @Test fun loginTrapsDown() = assertTrapped(Panel.Login, Key.DirectionDown)

    private fun assertTrapped(panel: Panel, direction: Key) {
        val app = ApplicationProvider.getApplicationContext<AuraApp>()
        val open = mutableStateOf(true)
        compose.setContent {
            AuraTheme {
                Box(Modifier.fillMaxSize()) {
                    BackgroundTarget("Behind left", Modifier.align(Alignment.CenterStart).width(32.dp).fillMaxHeight())
                    BackgroundTarget("Behind right", Modifier.align(Alignment.CenterEnd).width(32.dp).fillMaxHeight())
                    BackgroundTarget("Behind up", Modifier.align(Alignment.TopCenter).height(32.dp).fillMaxWidth())
                    BackgroundTarget("Behind down", Modifier.align(Alignment.BottomCenter).height(32.dp).fillMaxWidth())
                    if (open.value) when (panel) {
                        Panel.Bio -> BioDialog("Biography", null, "Biography text", { open.value = false })
                        Panel.Confirm -> ConfirmDialog("Confirm", "Continue?", "Proceed", {}, { open.value = false })
                        Panel.Context -> ContextMenuOverlay(
                            ContextMenuSpec("Actions", actions = listOf(ContextAction("Action", onSelect = {}))), { open.value = false })
                        Panel.Login -> LoginScreen(app, {}, debugDemoCode = "123456")
                        Panel.Queue -> {
                            BackHandler { open.value = false }
                            QueuePeekOverlay(listOf(Fixtures.track("Queued track")), 1, 1, 0L,
                                onExtend = {}, onCollapse = {}, onJump = {})
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1500)
        compose.runOnIdle {
            if (panel == Panel.Bio) assertEquals(AuraColors.Scrim, OverlayVisibility.dialogScrim)
            if (panel == Panel.Confirm) assertEquals(AuraColors.DialogScrim, OverlayVisibility.dialogScrim)
        }
        when (panel) {
            Panel.Bio -> compose.onNode(isFocused() and hasAnyDescendant(hasText("Biography text"))).assertExists()
            Panel.Confirm, Panel.Login -> compose.onNodeWithText("Cancel").assertIsFocused()
            Panel.Context -> compose.onNodeWithText("Action").assertIsFocused()
            Panel.Queue -> compose.onNodeWithText("Queued track").assertIsFocused()
        }
        compose.onNode(isFocused()).performKeyInput { pressKey(direction) }
        listOf("left", "right", "up", "down").forEach {
            compose.onNodeWithText("Behind $it").assertIsNotFocused()
        }
        if (panel == Panel.Login) compose.onNodeWithText("Cancel").assertIsFocused()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        if (panel == Panel.Login) {
            compose.onNodeWithText("123456").assertDoesNotExist()
        } else {
            compose.runOnIdle { assertFalse(open.value) }
            if (panel == Panel.Bio || panel == Panel.Confirm) {
                compose.runOnIdle {
                    assertFalse(OverlayVisibility.dialogOpen)
                    assertEquals(AuraColors.DialogScrim, OverlayVisibility.dialogScrim)
                }
            }
        }
    }

    @Composable
    private fun BackgroundTarget(label: String, modifier: Modifier) {
        Button(onClick = {}, modifier = modifier) { Text(label) }
    }
}
