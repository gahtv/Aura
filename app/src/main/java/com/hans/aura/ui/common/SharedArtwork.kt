package com.hans.aura.ui.common

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import com.hans.aura.ui.theme.AuraMotion
import com.hans.aura.ui.theme.AuraShapes

internal val LocalArtworkTransition = compositionLocalOf<SharedTransitionScope?> { null }
internal val LocalArtworkVisibility = compositionLocalOf<AnimatedVisibilityScope?> { null }
internal val LocalDestinationActive = compositionLocalOf { true }
internal val LocalArtworkSource = compositionLocalOf<MutableState<String?>?> { null }
internal val LocalArtworkRadius = compositionLocalOf { 12.dp }

@Composable
internal fun DestinationFocusGate(active: Boolean, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize()
            .onPreviewKeyEvent { !active }
            .focusProperties {
                onEnter = { if (!active) cancelFocusChange() }
            }
            .focusGroup()
    ) { content() }
}

@Composable
fun Modifier.sharedArtwork(key: String, shape: Shape = AuraShapes.card): Modifier {
    val shared = LocalArtworkTransition.current ?: return this
    val visibility = LocalArtworkVisibility.current ?: return this
    val clipShape = if (shape is RoundedCornerShape) RoundedCornerShape(LocalArtworkRadius.current) else shape
    return with(shared) {
        sharedElement(
            sharedContentState = rememberSharedContentState(key),
            animatedVisibilityScope = visibility,
            boundsTransform = { _, _ -> AuraMotion.scheme.defaultSpatialSpec() }
        ).clip(clipShape)
    }
}
