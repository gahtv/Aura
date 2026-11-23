package com.hans.aura.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTestConfig
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.hans.aura.ui.shell.NavigationRailLayout
import com.hans.aura.ui.common.FocusHandoffHost
import com.hans.aura.ui.common.InitialFocusOnPush
import com.hans.aura.ui.shell.RailDestination
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
class NavigationRailTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>(
        ComposeUiTestConfig(inputMode = InputMode.Keyboard)
    )

    @Test
    fun railExpandsWithoutShiftingContentAndRightRestoresContentFocus() {
        var selected by mutableStateOf(RailDestination.Home)
        renderRail(onSelect = { selected = it }, selected = { selected })

        val details = compose.onNodeWithText("Second card").assertIsFocused()
        val contentBounds = details.getUnclippedBoundsInRoot()
        val home = compose.onNodeWithContentDescription("Home").assertIsSelected()
        val collapsedWidth = home.width()

        details.performKeyInput { pressKey(Key.DirectionLeft) }
        home.assertIsFocused()
        assertTrue(home.width() > collapsedWidth)
        assertEquals(contentBounds, details.getUnclippedBoundsInRoot())

        home.performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithContentDescription("Library").assertIsFocused().assertIsNotSelected()
            .performKeyInput { pressKey(Key.DirectionDown) }
        val genres = compose.onNodeWithContentDescription("Genres").assertIsFocused()
        genres.performKeyInput { pressKey(Key.DirectionCenter) }
        genres.assertIsSelected()

        genres.performKeyInput { pressKey(Key.DirectionRight) }
        details.assertIsFocused()
        assertEquals(collapsedWidth, home.width())
        details.performKeyInput { pressKey(Key.DirectionLeft) }
        genres.assertIsFocused()
    }

    private fun SemanticsNodeInteraction.width() = getUnclippedBoundsInRoot().let { it.right - it.left }

    @Test
    fun automaticRailExpansionAndCollapseDoNotCancelPendingEntry() {
        val existing = FocusRequester()
        val loaded = FocusRequester()
        var ready by mutableStateOf(false)
        compose.setContent {
            AuraTheme {
                FocusHandoffHost {
                    NavigationRailLayout(true, RailDestination.Home, null, onSelect = {}) {
                        Column(Modifier.fillMaxSize().padding(start = 30.dp)) {
                            Button(onClick = {}, modifier = Modifier.focusRequester(existing)) { Text("Existing action") }
                            if (ready) Button(onClick = {}, modifier = Modifier.focusRequester(loaded)) { Text("Loaded card") }
                        }
                        LaunchedEffect(Unit) { existing.requestFocus() }
                        InitialFocusOnPush(true, loaded, ready = ready)
                    }
                }
            }
        }
        val home = compose.onNodeWithContentDescription("Home")
        val collapsedWidth = home.width()
        home.performSemanticsAction(SemanticsActions.RequestFocus)
        home.assertIsFocused()
        assertTrue(home.width() > collapsedWidth)
        compose.runOnIdle { existing.requestFocus() }
        compose.onNodeWithText("Existing action").assertIsFocused()
        assertEquals(collapsedWidth, home.width())
        compose.runOnIdle { ready = true }
        compose.onNodeWithText("Loaded card").assertIsFocused()
    }

    @Test
    fun backExitsRailWithoutSelectingAndHiddenRailReservesNoSpace() {
        var showRail by mutableStateOf(true)
        renderRail(showRail = { showRail }, onSelect = { error("Unexpected selection: $it") })
        val details = compose.onNodeWithText("Second card").assertIsFocused()
        val inset = details.getUnclippedBoundsInRoot().left

        details.performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNodeWithContentDescription("Home").assertIsFocused()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        details.assertIsFocused()

        compose.runOnIdle { showRail = false }
        compose.onNodeWithContentDescription("Home").assertDoesNotExist()
        assertTrue(details.getUnclippedBoundsInRoot().left < inset)
    }

    private fun renderRail(
        showRail: () -> Boolean = { true },
        onSelect: (RailDestination) -> Unit,
        selected: () -> RailDestination = { RailDestination.Home }
    ) {
        val detailFocus = FocusRequester()
        compose.setContent {
            AuraTheme {
                NavigationRailLayout(showRail(), selected(), playback = null, onSelect = onSelect) {
                    Column(
                        Modifier.fillMaxSize().padding(start = 30.dp, top = 90.dp),
                        verticalArrangement = Arrangement.spacedBy(180.dp)
                    ) {
                        Button(onClick = {}) { Text("First card") }
                        Button(onClick = {}, modifier = Modifier.focusRequester(detailFocus)) {
                            Text("Second card")
                        }
                    }
                    LaunchedEffect(Unit) { detailFocus.requestFocus() }
                }
            }
        }
    }
}
