package com.hans.aura.ui.common

import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.SecureTextField
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.hans.aura.ui.theme.AuraColors
import com.hans.aura.ui.theme.AuraShapes
import com.hans.aura.ui.theme.AuraSizes
import com.hans.aura.ui.theme.AuraTypography

@Composable
internal fun AuraTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    secure: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    onKeyboardAction: KeyboardActionHandler? = null
) {
    val state = rememberTextFieldState(value)
    val onChange by rememberUpdatedState(onValueChange)
    LaunchedEffect(value) {
        if (state.text.toString() != value) state.setTextAndPlaceCursorAtEnd(value)
    }
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }.collect { onChange(it) }
    }
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val colors = MaterialTheme.colorScheme
    val shape = AuraShapes.control

    val fieldModifier = modifier.border(
        width = AuraSizes.focusBorder,
        color = if (focused) AuraColors.CursorRing else Color.Transparent,
        shape = shape
    )
    val textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = AuraTypography.body)
    val fieldPlaceholder: @Composable () -> Unit = {
        Text(placeholder, color = colors.onSurfaceVariant, fontSize = AuraTypography.body)
    }
    val fieldColors = TextFieldDefaults.colors(
        focusedContainerColor = AuraColors.FocusedFill,
        unfocusedContainerColor = AuraColors.PillBackgroundRaised,
        focusedTextColor = colors.onSurface,
        unfocusedTextColor = colors.onSurface,
        cursorColor = colors.primary,
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent
    )
    if (secure) {
        SecureTextField(
            state = state,
            modifier = fieldModifier,
            textStyle = textStyle,
            placeholder = fieldPlaceholder,
            shape = shape,
            colors = fieldColors,
            interactionSource = interactionSource,
            textObfuscationMode = TextObfuscationMode.Hidden,
            textObfuscationCharacter = '\u2022',
            keyboardOptions = keyboardOptions,
            onKeyboardAction = onKeyboardAction
        )
    } else {
        TextField(
            state = state,
            modifier = fieldModifier,
            textStyle = textStyle,
            placeholder = fieldPlaceholder,
            lineLimits = TextFieldLineLimits.SingleLine,
            shape = shape,
            colors = fieldColors,
            interactionSource = interactionSource,
            keyboardOptions = keyboardOptions,
            onKeyboardAction = onKeyboardAction
        )
    }
}
