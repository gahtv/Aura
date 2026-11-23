package com.hans.aura.ui.common

import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.input.ImeAction
import com.hans.aura.ui.theme.AuraTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land")
class AuraTextFieldTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun editsAndExternalResetStayInSync() {
        val value = mutableStateOf("")
        compose.setContent {
            AuraTheme {
                AuraTextField(value.value, { value.value = it }, "Search", Modifier.testTag("field"))
            }
        }
        compose.onNodeWithTag("field").performTextInput("Aura")
        compose.runOnIdle { assertEquals("Aura", value.value) }
        compose.onNodeWithTag("field").performTextClearance()
        compose.runOnIdle { assertEquals("", value.value) }
        compose.runOnIdle { value.value = "River" }
        compose.onNodeWithTag("field").assertTextEquals("River")
    }

    @Test
    fun securePasswordSubmitsCurrentValueAndCanBeCorrected() {
        val value = mutableStateOf("")
        var submitted = ""
        compose.setContent {
            AuraTheme {
                AuraTextField(
                    value.value, { value.value = it }, "Password", Modifier.testTag("field"),
                    secure = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    onKeyboardAction = { submitted = value.value }
                )
            }
        }
        compose.onNodeWithTag("field").performTextInput("wrong")
        compose.onNodeWithTag("field").performImeAction()
        compose.runOnIdle { assertEquals("wrong", submitted) }
        compose.onNodeWithTag("field").performTextClearance()
        compose.onNodeWithTag("field").performTextInput("correct")
        compose.onNodeWithTag("field").performImeAction()
        compose.runOnIdle { assertEquals("correct", submitted) }
    }
}
