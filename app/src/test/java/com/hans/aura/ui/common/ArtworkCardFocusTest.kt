package com.hans.aura.ui.common

import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.ComposeUiTestConfig
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.hans.aura.data.Fixtures
import com.hans.aura.ui.theme.AuraMotion
import com.hans.aura.ui.theme.AuraTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land")
class ArtworkCardFocusTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(
        ComposeUiTestConfig(inputMode = InputMode.Keyboard)
    )

    @Test
    fun horizontalFocusKeepsGridStationaryWhileCardScales() {
        shadowOf(compose.activity.packageManager)
            .setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
        compose.mainClock.autoAdvance = false
        val state = LazyGridState()
        val focusKeeper = FocusKeeper()
        val albums = List(30) { Fixtures.album("Album $it") }
        compose.setContent {
            AuraTheme {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
                    state = state,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        horizontal = 32.dp,
                        vertical = AuraMotion.artworkFocusInset(210.dp)
                    ),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    items(albums.size) { index ->
                        ArtworkCard(
                            item = albums[index],
                            serverUrl = "",
                            size = 210.dp,
                            focusKeeper = focusKeeper,
                            onClick = {}
                        )
                    }
                }
            }
        }

        fun position() = compose.runOnIdle {
            state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset
        }

        compose.runOnIdle { focusKeeper.requesterFor("Album 6").requestFocus() }
        compose.mainClock.advanceTimeBy(600)
        val initialPosition = position()
        assertTrue("Expected a partially visible row above the focused card", initialPosition.second > 0)

        compose.onNodeWithText("Album 6").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionRight) }
        val positions = List(24) {
            compose.mainClock.advanceTimeByFrame()
            position()
        }
        compose.onNodeWithText("Album 7").assertIsFocused()
        assertEquals(listOf(initialPosition), positions.distinct())

        compose.onNodeWithText("Album 7").performKeyInput { pressKey(Key.DirectionDown) }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("Album 12").assertIsFocused()
        assertNotEquals(initialPosition, position())
    }
}
