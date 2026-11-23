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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import coil.request.ImageRequest
import com.hans.aura.ui.theme.AuraMotion

/** Shared artwork host, absent outside destination navigation. */
internal val LocalArtworkTransition = compositionLocalOf<SharedTransitionScope?> { null }
/** Visibility of the owning destination entry. */
internal val LocalArtworkVisibility = compositionLocalOf<AnimatedVisibilityScope?> { null }
/** Whether this destination may accept focus. */
internal val LocalDestinationActive = compositionLocalOf { true }
/** Composition key of the selected artwork occurrence. */
internal val LocalArtworkSource = compositionLocalOf<MutableState<Long?>?> { null }
/** Corner endpoints for matching artwork within one transition host. */
internal val LocalArtworkCorners = compositionLocalOf<MutableMap<String, ArtworkCorners>?> { null }

internal class ArtworkCorners {
    val endpoints = mutableStateMapOf<Any, Dp>()

    fun otherRadius(endpoint: Any, fallback: Dp): Dp =
        endpoints.entries.firstOrNull { it.key !== endpoint }?.value ?: fallback
}

@Composable
internal fun artworkRequest(url: String?, placeholderKey: String? = url): ImageRequest {
    val context = LocalContext.current
    return remember(context, url, placeholderKey) {
        ImageRequest.Builder(context).data(url)
            .memoryCacheKey(url)
            .placeholderMemoryCacheKey(placeholderKey)
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
fun Modifier.sharedArtwork(key: String, cornerRadius: Dp): Modifier {
    val shared = LocalArtworkTransition.current ?: return this
    val visibility = LocalArtworkVisibility.current ?: return this
    val registry = LocalArtworkCorners.current ?: return this
    val corners = remember(registry, key) { registry.getOrPut(key) { ArtworkCorners() } }
    val endpoint = remember { Any() }
    DisposableEffect(corners, endpoint, cornerRadius) {
        registry[key] = corners
        corners.endpoints[endpoint] = cornerRadius
        onDispose {
            corners.endpoints.remove(endpoint)
            if (corners.endpoints.isEmpty()) registry.remove(key, corners)
        }
    }
    val otherRadius = corners.otherRadius(endpoint, cornerRadius)
    val radius by visibility.transition.animateDp(
        transitionSpec = { AuraMotion.scheme.defaultSpatialSpec() },
        label = "artworkCorners"
    ) { state ->
        if (state == EnterExitState.Visible) cornerRadius else otherRadius
    }
    return with(shared) {
        sharedElement(
            sharedContentState = rememberSharedContentState(key),
            animatedVisibilityScope = visibility,
            boundsTransform = { _, _ -> AuraMotion.scheme.defaultSpatialSpec() }
        ).clip(RoundedCornerShape(radius))
    }
}
