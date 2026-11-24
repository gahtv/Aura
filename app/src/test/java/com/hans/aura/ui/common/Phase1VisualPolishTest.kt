package com.hans.aura.ui.common

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.ComposeUiTestConfig
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.dp
import com.hans.aura.data.Fixtures
import com.hans.aura.data.JellyfinItem
import com.hans.aura.ui.shell.PlayingEqualizerIndicator
import com.hans.aura.ui.theme.Accent
import com.hans.aura.ui.theme.AuraTheme
import org.junit.Assert.assertEquals
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
        val artist = com.hans.aura.model.Artist(id = "art1", name = "boygenius")
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
    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    fun trackRowActionsAreAlwaysVisibleAndFocusable() {
        val track = Fixtures.domainTrack("Atmos Track")
        val keeper = FocusKeeper()
        var menuOpens = 0
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
                        onToggleFavorite = {},
                        onLongPress = { menuOpens++ }
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

        // The like and overflow pills live after the duration and are always
        // composed: no focus-reveal, so nothing shifts when the row gains or
        // loses focus (the old hint block slid the duration sideways).
        compose.onNodeWithText("\u2022\u2022\u2022").assertExists()
        compose.onNodeWithContentDescription("Add to favorites").assertExists()

        // Row content stays the primary D-pad target; the keeper still
        // restores focus to it.
        compose.runOnIdle { keeper.requesterFor(track.id).requestFocus() }
        compose.onNodeWithText("Atmos Track").assertIsFocused()

        // And the pills are REAL D-pad targets — the previous design kept them
        // unreachable inside the clickable row. Right walks row -> like -> more.
        compose.onNodeWithText("Atmos Track").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithContentDescription("Add to favorites").assertIsFocused()
        compose.onNodeWithContentDescription("Add to favorites").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithText("\u2022\u2022\u2022").assertIsFocused()

        // OK on the pill opens the menu (focus return through the real overlay
        // is covered by ContextMenuFocusReturnTest).
        compose.onNodeWithText("\u2022\u2022\u2022").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(1, menuOpens) }

        // Leaving the row keeps both affordances on screen.
        compose.onNodeWithText("Other").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithText("\u2022\u2022\u2022").assertExists()
        compose.onNodeWithContentDescription("Add to favorites").assertExists()
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
