package com.hans.aura.ui.common

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.hans.aura.ui.theme.AuraTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land")
class AuraLoadingIndicatorTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun loadingStatesExposeIndeterminateProgressWithoutTakingFocus() {
        val label = mutableStateOf("Loading library")
        compose.setContent {
            AuraTheme { AuraLoadingIndicator(label.value) }
        }
        for (description in listOf("Loading library", "Loading more tracks", "Loading more library items", "Loading lyrics")) {
            compose.runOnIdle { label.value = description }
            compose.onNodeWithContentDescription(description)
                .assertIsDisplayed()
                .assert(SemanticsMatcher.expectValue(
                    SemanticsProperties.ProgressBarRangeInfo,
                    ProgressBarRangeInfo.Indeterminate
                ))
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Focused))
        }
    }
}
