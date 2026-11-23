package com.hans.aura.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.hans.aura.ui.theme.Accent
import com.hans.aura.ui.theme.AuraColors
import com.hans.aura.ui.theme.AuraMotion
import com.hans.aura.ui.theme.AuraShapes
import com.hans.aura.ui.theme.AuraSizes
import com.hans.aura.ui.theme.AuraSpacing
import com.hans.aura.ui.theme.SurfaceHigh
import com.hans.aura.ui.theme.TextPrimary

internal data class ConnectedAction(
    val label: String,
    val onClick: () -> Unit,
    val modifier: Modifier = Modifier,
    val active: Boolean = false,
    val fill: Color = Color.Unspecified,
    val contentColor: Color = AuraColors.OnAccent
)

@Composable
internal fun ConnectedActions(actions: List<ConnectedAction>, compact: Boolean = false) {
    val requesters = remember(actions.size) { List(actions.size) { FocusRequester() } }
    Row(
        horizontalArrangement = Arrangement.spacedBy(AuraSpacing.xxs / 2),
        verticalAlignment = Alignment.CenterVertically
    ) {
        actions.forEachIndexed { index, action ->
            // Keep D-pad moves inside the group until its edge, even beside offset controls.
            val navigation = Modifier
                .focusRequester(requesters[index])
                .focusProperties {
                    requesters.getOrNull(index - 1)?.let { start = it }
                    requesters.getOrNull(index + 1)?.let { end = it }
                }
            ConnectedSegment(action, index == 0, index == actions.lastIndex, compact, navigation)
        }
    }
}

@Composable
private fun ConnectedSegment(
    action: ConnectedAction,
    start: Boolean,
    end: Boolean,
    compact: Boolean,
    navigation: Modifier
) {
    var focused by remember { mutableStateOf(false) }
    val inner by animateDpAsState(
        if (focused) AuraShapes.pillFocusRadius else AuraShapes.connectedInnerRadius,
        animationSpec = AuraMotion.scheme.fastSpatialSpec(),
        label = "connectedActionCorner"
    )
    val shape = remember(start, end, inner) {
        val leading = if (start) AuraShapes.pillRestRadius else inner
        val trailing = if (end) AuraShapes.pillRestRadius else inner
        RoundedCornerShape(leading, trailing, trailing, leading)
    }
    val fill by animateColorAsState(
        if (focused) AuraColors.FocusedFill else SurfaceHigh,
        animationSpec = AuraMotion.scheme.fastEffectsSpec(),
        label = "connectedActionFill"
    )
    Box(
        action.modifier
            .then(navigation)
            .height(if (compact) AuraSizes.compactHeaderActionHeight else AuraSizes.minTarget)
            .clip(shape)
            .background(if (action.fill.isSpecified) action.fill else fill)
            .border(AuraSizes.focusBorder, if (focused) AuraColors.CursorRing else Color.Transparent, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(role = Role.Button, onClick = action.onClick)
            .padding(horizontal = if (compact) AuraSizes.compactHeaderActionPadding else AuraSpacing.md),
        contentAlignment = Alignment.Center
    ) {
        Text(
            action.label,
            style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleSmall,
            color = when {
                action.fill.isSpecified -> action.contentColor
                action.active -> Accent
                else -> TextPrimary
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
