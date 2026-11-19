package com.hans.aura.ui.common

import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.VisualTransformation
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
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val colors = MaterialTheme.colorScheme
    val shape = AuraShapes.control

    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.border(
            width = AuraSizes.focusBorder,
            color = if (focused) AuraColors.CursorRing else Color.Transparent,
            shape = shape
        ),
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = AuraTypography.body),
        placeholder = {
            Text(placeholder, color = colors.onSurfaceVariant, fontSize = AuraTypography.body)
        },
        singleLine = true,
        shape = shape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = AuraColors.FocusedFill,
            unfocusedContainerColor = AuraColors.PillBackgroundRaised,
            focusedTextColor = colors.onSurface,
            unfocusedTextColor = colors.onSurface,
            cursorColor = colors.primary,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent
        ),
        interactionSource = interactionSource,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions
    )
}
