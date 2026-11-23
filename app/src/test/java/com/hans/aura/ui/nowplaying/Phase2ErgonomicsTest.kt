package com.hans.aura.ui.nowplaying

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.ComposeUiTestConfig
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import com.hans.aura.AuraApp
import com.hans.aura.data.Fixtures
import com.hans.aura.data.JellyfinItem
import com.hans.aura.data.Prefs
import com.hans.aura.data.Session
import com.hans.aura.playback.PlaybackService
import com.hans.aura.player.controller.PlaybackController
import com.hans.aura.ui.player.PlayerConnection
import com.hans.aura.ui.theme.Accent
import com.hans.aura.ui.theme.AuraTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
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
class Phase2ErgonomicsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>(
        ComposeUiTestConfig(inputMode = InputMode.Keyboard)
    )

    @Before
    fun resetPrefs() {
        val app = ApplicationProvider.getApplicationContext<AuraApp>()
        Prefs.init(app)
        Prefs.ambientNowPlaying = true
        Prefs.nowPlayingDimMode = false
    }

    @Test
    fun prefsPersistAmbientAndDimMode() {
        assertTrue(Prefs.ambientNowPlaying)
        assertFalse(Prefs.nowPlayingDimMode)

        Prefs.ambientNowPlaying = false
        Prefs.nowPlayingDimMode = true
        assertFalse(Prefs.ambientNowPlaying)
        assertTrue(Prefs.nowPlayingDimMode)
    }

    @Test
    fun dimModeToggleUpdatesState() {
        val app = ApplicationProvider.getApplicationContext<AuraApp>()
        shadowOf(app).declareComponentUnbindable(ComponentName(app, PlaybackService::class.java))
        val player = PlayerConnection(app, app.jellyfin) { app.jellyfin.auth.session }
        compose.mainClock.autoAdvance = false

        var toggled = false
        compose.setContent {
            AuraTheme {
                var dim by remember { mutableStateOf(false) }
                TransportBar(
                    player = player,
                    position = 0,
                    duration = 0,
                    isPlaying = false,
                    playPauseFocus = remember { FocusRequester() },
                    accent = Accent,
                    onOpenPeek = { false },
                    dimMode = dim,
                    onToggleDimMode = {
                        dim = !dim
                        toggled = dim
                    }
                )
            }
        }
        compose.onNodeWithContentDescription("Dim mode").performClick()
        compose.runOnIdle { assertTrue(toggled) }
    }

    @Test
    fun centerWakeDoesNotActivateTransportButNextPressDoes() {
        assertWakeDoesNotActivateTransport(Key.DirectionCenter)
    }

    @Test
    fun leftWakeDoesNotActivateTransportButNextPressDoes() {
        assertWakeDoesNotActivateTransport(Key.DirectionLeft)
    }

    @Test
    fun rightWakeDoesNotActivateTransportButNextPressDoes() {
        assertWakeDoesNotActivateTransport(Key.DirectionRight)
    }

    private fun assertWakeDoesNotActivateTransport(wakeKey: Key) {
        val app = ApplicationProvider.getApplicationContext<AuraApp>()
        app.jellyfin.auth.installSession(Session("http://example.test", "user", "test", "token", "device"))
        shadowOf(app).declareComponentUnbindable(ComponentName(app, PlaybackService::class.java))
        val player = PlayerConnection(app, app.jellyfin) { app.jellyfin.auth.session }
        val engine = ExoPlayer.Builder(app).build()
        val session = MediaSession.Builder(app, engine).build()
        val future = MediaController.Builder(app, session.token).buildAsync()
        compose.waitUntil { shadowOf(android.os.Looper.getMainLooper()).idle(); future.isDone }
        val controller = future.get()
        val playback = ReflectionHelpers.getField<PlaybackController>(player, "playback")
        ReflectionHelpers.getField<MutableStateFlow<MediaController?>>(playback, "_controller").value = controller
        ReflectionHelpers.getField<MutableStateFlow<JellyfinItem?>>(player, "_currentItem").value =
            Fixtures.track("Ambient Song")
        ReflectionHelpers.getField<MutableStateFlow<Boolean>>(player, "_isPlaying").value = true

        try {
            compose.mainClock.autoAdvance = false
            compose.setContent {
                AuraTheme {
                    NowPlayingScreen(app = app, player = player, onBack = {}, onOpenQueue = {})
                }
            }
            compose.mainClock.advanceTimeBy(500, ignoreFrameDuration = true)
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(6_500, ignoreFrameDuration = true)
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(400, ignoreFrameDuration = true)
            compose.mainClock.advanceTimeByFrame()
            compose.onAllNodesWithText("Ambient Song").assertCountEquals(2)

            compose.onNodeWithContentDescription("Pause").performKeyInput { keyDown(wakeKey) }
            compose.mainClock.advanceTimeBy(400, ignoreFrameDuration = true)
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithContentDescription("Pause").performKeyInput { keyUp(wakeKey) }
            compose.runOnIdle {
                assertFalse("Wake KeyUp must not activate the transport button", controller.playWhenReady)
            }
            compose.onAllNodesWithText("Ambient Song").assertCountEquals(1)
            compose.onNodeWithContentDescription("Pause").performKeyInput { pressKey(Key.DirectionCenter) }
            compose.runOnIdle { assertTrue("The next Center press must activate transport", controller.playWhenReady) }
        } finally {
            controller.release()
            session.release()
            engine.release()
            player.teardown()
        }
    }

    @Test
    fun ambientModeHidesHudAfterIdleAndWakesOnDpad() {
        val app = ApplicationProvider.getApplicationContext<AuraApp>()
        app.jellyfin.auth.installSession(Session("http://example.test", "user", "test", "token", "device"))
        shadowOf(app).declareComponentUnbindable(ComponentName(app, PlaybackService::class.java))
        val player = PlayerConnection(app, app.jellyfin) { app.jellyfin.auth.session }
        ReflectionHelpers.getField<MutableStateFlow<JellyfinItem?>>(player, "_currentItem").value =
            Fixtures.track("Ambient Song")
        ReflectionHelpers.getField<MutableStateFlow<Boolean>>(player, "_isPlaying").value = true

        compose.mainClock.autoAdvance = false
        compose.setContent {
            AuraTheme {
                NowPlayingScreen(app = app, player = player, onBack = {}, onOpenQueue = {})
            }
        }
        compose.mainClock.advanceTimeBy(500, ignoreFrameDuration = true)
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
        compose.onAllNodesWithText("Ambient Song").assertCountEquals(1)

        // Advance past the 6s ambient timeout in one jump (avoiding 400 WavySeekBar frames).
        compose.mainClock.advanceTimeBy(6_500, ignoreFrameDuration = true)
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(400, ignoreFrameDuration = true)
        compose.mainClock.advanceTimeByFrame()
        compose.onAllNodesWithText("Ambient Song").assertCountEquals(2)

        // Any D-pad key wakes the HUD immediately.
        compose.onNodeWithContentDescription("Pause")
            .performKeyInput { pressKey(Key.DirectionRight) }
        compose.mainClock.advanceTimeBy(400, ignoreFrameDuration = true)
        compose.mainClock.advanceTimeByFrame()
        compose.onAllNodesWithText("Ambient Song").assertCountEquals(1)
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
    }
}
