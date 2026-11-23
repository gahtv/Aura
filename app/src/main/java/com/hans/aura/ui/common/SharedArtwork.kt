package com.hans.aura.ui.common

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateDp
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import coil.request.ImageRequest
import com.hans.aura.ui.theme.AuraMotion
import com.hans.aura.ui.theme.AuraShapes

/** Shared artwork host, absent outside destination navigation. */
internal val LocalArtworkTransition = compositionLocalOf<SharedTransitionScope?> { null }
/** Visibility of the owning destination entry. */
internal val LocalArtworkVisibility = compositionLocalOf<AnimatedVisibilityScope?> { null }
/** Whether this destination may accept focus. */
internal val LocalDestinationActive = compositionLocalOf { true }
/** Composition key of the selected artwork occurrence. */
internal val LocalArtworkSource = compositionLocalOf<MutableState<Int?>?> { null }

@Composable
internal fun artworkRequest(url: String?): ImageRequest {
    val context = LocalContext.current
    return remember(context, url) {
        ImageRequest.Builder(context).data(url)
            .memoryCacheKey(url)
            .placeholderMemoryCacheKey(url)
            .build()
    }
}

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

/** Shares an artwork tile by item ID, or preserves the modifier outside a host. */
@Composable
fun Modifier.sharedArtwork(key: String, shape: Shape = AuraShapes.card): Modifier {
    val shared = LocalArtworkTransition.current ?: return this
    val visibility = LocalArtworkVisibility.current ?: return this
    val density = LocalDensity.current
    val cardRadius = with(density) { AuraShapes.card.topStart.toPx(Size.Zero, this).toDp() }
    val heroRadius = with(density) { AuraShapes.cardHero.topStart.toPx(Size.Zero, this).toDp() }
    val radius by visibility.transition.animateDp(
        transitionSpec = { AuraMotion.scheme.defaultSpatialSpec() },
        label = "artworkCorners"
    ) { state ->
        if ((state == EnterExitState.Visible) == (shape == AuraShapes.cardHero)) heroRadius else cardRadius
    }
    val clipShape = if (shape == AuraShapes.card || shape == AuraShapes.cardHero) RoundedCornerShape(radius) else shape
    return with(shared) {
        sharedElement(
            sharedContentState = rememberSharedContentState(key),
            animatedVisibilityScope = visibility,
            boundsTransform = { _, _ -> AuraMotion.scheme.defaultSpatialSpec() }
        ).clip(clipShape)
    }
}
