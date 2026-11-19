package com.hans.aura.ui.nowplaying

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.hans.aura.data.LyricCredits
import com.hans.aura.ui.theme.AuraTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LyricCreditsFooterTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun showsEachCreditWithItsOwnRoleAndHidesMissingCredits() {
        val credits = mutableStateOf(LyricCredits(listOf("Ada Lane", "Ren Park"), listOf("Sam Ford"), "Performer"))
        compose.setContent { AuraTheme { LyricCreditsFooter(credits.value) } }

        compose.onNodeWithText("Written by: Ada Lane, Ren Park").assertIsDisplayed()
        compose.onNodeWithText("Lyrics transcribed by: Sam Ford").assertIsDisplayed()
        compose.onNodeWithText("Performer", substring = true).assertDoesNotExist()

        compose.runOnIdle { credits.value = LyricCredits(artist = "Another Performer") }
        compose.onNodeWithText("Written by:", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Lyrics transcribed by:", substring = true).assertDoesNotExist()
    }
}
