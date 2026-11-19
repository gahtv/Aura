package com.hans.aura.ui.nowplaying

import android.content.ComponentName
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import com.hans.aura.AuraApp
import com.hans.aura.playback.PlaybackService
import com.hans.aura.ui.player.PlayerConnection
import com.hans.aura.ui.theme.Accent
import com.hans.aura.ui.theme.AuraTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land")
class TransportBarFocusTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun downFromPauseFocusesShuffleBeforeOpeningQueue() {
        var opens = 0
        showTransport { ++opens }

        press("Pause", Key.DirectionDown)
        compose.runOnIdle { assertEquals(0, opens) }
        press("Shuffle", Key.DirectionDown)

        compose.runOnIdle { assertEquals(1, opens) }
    }

    @Test
    fun downFromRepeatOpensQueue() {
        var opens = 0
        showTransport { ++opens }

        press("Pause", Key.DirectionDown)
        press("Shuffle", Key.DirectionRight)
        compose.runOnIdle { assertEquals(0, opens) }
        press("Repeat off", Key.DirectionDown)

        compose.runOnIdle { assertEquals(1, opens) }
    }

    private fun showTransport(onOpen: () -> Unit) {
        val app = ApplicationProvider.getApplicationContext<AuraApp>()
        shadowOf(app).declareComponentUnbindable(ComponentName(app, PlaybackService::class.java))
        val player = PlayerConnection(app, app.jellyfin) { null }
        compose.setContent {
            AuraTheme {
                TransportBar(
                    player = player,
                    position = 0,
                    duration = 0,
                    isPlaying = true,
                    playPauseFocus = remember { FocusRequester() },
                    accent = Accent,
                    onOpenPeek = { onOpen(); true },
                    onToggleFavorite = {},
                    onRadio = {}
                )
            }
        }
        compose.onNodeWithContentDescription("Pause").performSemanticsAction(SemanticsActions.RequestFocus)
    }

    private fun press(focusedLabel: String, key: Key) {
        compose.onNodeWithContentDescription(focusedLabel)
            .assertIsFocused()
            .performKeyInput { pressKey(key) }
    }
}
