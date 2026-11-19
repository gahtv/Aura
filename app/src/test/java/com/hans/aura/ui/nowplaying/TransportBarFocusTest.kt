package com.hans.aura.ui.nowplaying

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import com.hans.aura.AuraApp
import com.hans.aura.data.Fixtures
import com.hans.aura.data.JellyfinItem
import com.hans.aura.data.Session
import com.hans.aura.playback.PlaybackService
import com.hans.aura.ui.player.PlayerConnection
import com.hans.aura.ui.theme.Accent
import com.hans.aura.ui.theme.AuraTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land")
class TransportBarFocusTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

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

    @Test
    fun backFromQueueRestoresShuffleFocus() {
        showNowPlaying()
        press("Play", Key.DirectionDown)
        press("Shuffle", Key.DirectionDown)

        dismissQueueAndAssertFocus("Shuffle")
    }

    @Test
    fun backFromQueueRestoresRepeatFocus() {
        showNowPlaying()
        press("Play", Key.DirectionDown)
        press("Shuffle", Key.DirectionRight)
        press("Repeat off", Key.DirectionDown)

        dismissQueueAndAssertFocus("Repeat off")
    }

    private fun showNowPlaying() {
        val app = ApplicationProvider.getApplicationContext<AuraApp>()
        app.jellyfin.auth.installSession(Session("http://example.test", "user", "test", "token", "device"))
        val player = newPlayer(app)
        ReflectionHelpers.getField<MutableStateFlow<List<JellyfinItem>>>(player, "_queue").value =
            listOf(Fixtures.track("Current track"), Fixtures.track("Queued track"))
        compose.setContent {
            AuraTheme {
                NowPlayingScreen(app = app, player = player, onBack = {}, onOpenQueue = {})
            }
        }
        compose.mainClock.advanceTimeBy(500)
    }

    private fun dismissQueueAndAssertFocus(label: String) {
        compose.onNodeWithText("Up next").assertExists()
        val queuedTrack = compose.onNodeWithText("Queued track")
        queuedTrack.performSemanticsAction(SemanticsActions.RequestFocus).assertIsFocused()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Up next").assertDoesNotExist()
        val origin = compose.onNodeWithContentDescription(label)
        compose.waitUntil(5_000) {
            origin.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) == true
        }
    }

    private fun showTransport(onOpen: () -> Unit) {
        val app = ApplicationProvider.getApplicationContext<AuraApp>()
        val player = newPlayer(app)
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

    private fun newPlayer(app: AuraApp): PlayerConnection {
        shadowOf(app).declareComponentUnbindable(ComponentName(app, PlaybackService::class.java))
        return PlayerConnection(app, app.jellyfin) { app.jellyfin.auth.session }
    }

    private fun press(focusedLabel: String, key: Key) {
        compose.onNodeWithContentDescription(focusedLabel)
            .assertIsFocused()
            .performKeyInput { pressKey(key) }
    }
}
