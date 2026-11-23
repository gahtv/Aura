package com.hans.aura.ui.common

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.ComposeUiTestConfig
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import com.hans.aura.data.Fixtures
import com.hans.aura.data.JellyfinItem
import com.hans.aura.ui.shell.PlayingEqualizerIndicator
import com.hans.aura.ui.theme.Accent
import com.hans.aura.ui.theme.AuraTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land")
class Phase1VisualPolishTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>(
        ComposeUiTestConfig(inputMode = InputMode.Keyboard)
    )

    @Test
    fun circularArtistCardAcceptsFocus() {
        val artist = JellyfinItem(id = "art1", name = "boygenius", type = "MusicArtist")
        val keeper = FocusKeeper()
        compose.setContent {
            AuraTheme {
                ArtworkCard(
                    item = artist,
                    serverUrl = "",
                    size = 180.dp,
                    focusKeeper = keeper,
                    onClick = {}
                )
            }
        }
        compose.runOnIdle { keeper.requesterFor("art1").requestFocus() }
        compose.onNodeWithText("boygenius").assertIsFocused()
        compose.onNodeWithText("Artist").assertExists()
    }

    @Test
    fun trackRowRevealsInlineActionHintsOnlyWhenFocused() {
        val track = Fixtures.track("Atmos Track")
        val keeper = FocusKeeper()
        val otherFocus = FocusRequester()
        compose.setContent {
            AuraTheme {
                Column {
                    TrackRow(
                        index = 1,
                        track = track,
                        isCurrent = false,
                        onClick = {},
                        focusKeeper = keeper,
                        favorite = false,
                        onLongPress = {}
                    )
                    androidx.tv.material3.Button(
                        onClick = {},
                        modifier = androidx.compose.ui.Modifier
                    ) {
                        androidx.tv.material3.Text("Other")
                    }
                }
            }
        }

        compose.onNodeWithText("\u2022\u2022\u2022").assertDoesNotExist()
        compose.onNodeWithText("\u2665").assertDoesNotExist()

        compose.runOnIdle { keeper.requesterFor(track.id).requestFocus() }
        compose.onNodeWithText("\u2022\u2022\u2022").assertExists()
        compose.onNodeWithText("\u2665").assertExists()

        compose.onNodeWithText("Other").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithText("\u2022\u2022\u2022").assertDoesNotExist()
    }

    @Test
    fun equalizerIndicatorShowsPausedWhenNotPlaying() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AuraTheme {
                Column {
                    PlayingEqualizerIndicator(isPlaying = false, tint = Accent)
                }
            }
        }
        compose.onNodeWithContentDescription("Paused").assertExists()
    }

    @Test
    fun equalizerIndicatorHidesPausedIconWhenPlaying() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AuraTheme {
                Column {
                    PlayingEqualizerIndicator(isPlaying = true, tint = Accent)
                }
            }
        }
        compose.onNodeWithContentDescription("Paused").assertDoesNotExist()
    }
}
