package com.hans.aura.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import com.hans.aura.R
import com.hans.aura.ui.theme.AuraColors
import com.hans.aura.ui.theme.AuraMotion
import com.hans.aura.ui.theme.AuraShapes
import com.hans.aura.ui.theme.AuraSizes
import com.hans.aura.ui.theme.AuraSpacing
import com.hans.aura.ui.theme.AuraTypography
import com.hans.aura.ui.theme.TextPrimary
import com.hans.aura.ui.theme.TextSecondary
import com.hans.aura.data.SourceQueries

/**
 * Small heart (filled accent = liked, hollow grey = not).
 *
 * Two modes on purpose:
 *  - `interactive = false` on cards and rows: a pure INDICATOR. A focusable
 *    child inside a focusable card is unreachable with a D-pad (focus search
 *    does not descend into a clickable parent), so a heart you could only hit
 *    with a finger is a trap on a TV. Favouriting is done from the context
 *    menu (hold OK) or from the Now Playing transport row.
 *  - `interactive = true` where it sits in a normal button row (Now Playing):
 *    then it carries the app cursor language, and `.onFocusChanged {}` must
 *    come BEFORE `.clickable {}` (after it the flag never flips and the ring
 *    silently never renders).
 */
@Composable
fun HeartToggle(
    favorite: Boolean,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    iconSize: Dp = AuraSizes.iconSmall,
    interactive: Boolean = true
) {
    val colors = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    val shape = AuraShapes.pill
    val focusableModifier = if (!interactive) {
        modifier
    } else {
        modifier
            .clip(shape)
            .background(
                when {
                    focused -> colors.secondaryContainer
                    else -> Color.Transparent
                }
            )
            .border(
                width = if (focused) AuraSizes.focusBorder else 0.dp,
                color = if (focused) AuraColors.CursorRing else Color.Transparent,
                shape = shape
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                enabled = onClick != null,
                onClickLabel = if (favorite) "Remove from favorites" else "Add to favorites"
            ) { onClick?.invoke() }
    }
    Box(
        focusableModifier.padding(horizontal = AuraSpacing.xs, vertical = AuraSpacing.xxs),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.material3.Icon(
            painter = painterResource(
                if (favorite) R.drawable.ic_favorite_fill1
                else R.drawable.ic_favorite
            ),
            contentDescription = null,
            tint = if (focused) colors.onSecondaryContainer else if (favorite) colors.primary else TextSecondary,
            modifier = Modifier.size(iconSize)
        )
    }
}

/**
 * "Only Atmos" view filter, icon-only on purpose (user decision 2026-11-15): the
 * library pill row already carries 8-9 pills and the artist header is tight, so a
 * text label does not fit. spatial_audio = filter on, spatial_audio_off = off.
 * The wording lives in Settings, which explains the feature in words.
 *
 * House rule: `.onFocusChanged` BEFORE `.clickable`, or the focus ring never
 * renders and the control reads as dead on a TV.
 */
@Composable
fun AtmosFilterPill(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    val shape = filterPillShape(selected, focused)
    val fill by animateColorAsState(
        if (focused) AuraColors.FocusedFillSubtle else Color.Transparent,
        animationSpec = AuraMotion.scheme.fastEffectsSpec(),
        label = "atmosFilterFill"
    )
    Box(
        modifier
            .semantics { this.selected = selected }
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClickLabel = "Only Atmos") { onClick() }
            .clip(shape)
            .background(if (selected) colors.primary else fill)
            .border(
                width = if (focused) AuraSizes.focusBorderLoud else 0.dp,
                color = if (focused) TextPrimary else Color.Transparent,
                shape = shape
            )
            .padding(horizontal = AuraSpacing.sm, vertical = AuraSpacing.xs),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.material3.Icon(
            painter = painterResource(
                if (selected) R.drawable.ic_spatial_audio
                else R.drawable.ic_spatial_audio_off
            ),
            contentDescription = "Only Atmos",
            tint = when {
                selected -> colors.onPrimary
                else -> TextPrimary
            },
            modifier = Modifier.size(AuraSizes.iconBadge)
        )
    }
}

@Composable
internal fun filterPillShape(selected: Boolean, focused: Boolean): RoundedCornerShape {
    val radius by animateDpAsState(
        when {
            focused && selected -> AuraShapes.pillSelectedFocusRadius
            focused -> AuraShapes.pillFocusRadius
            selected -> AuraShapes.cardRadius
            else -> AuraShapes.pillRestRadius
        },
        animationSpec = AuraMotion.scheme.fastSpatialSpec(),
        label = "filterPillCorner"
    )
    return remember(radius) { RoundedCornerShape(radius.coerceAtLeast(0.dp)) }
}

/** Small ATMOS marker used on rows (the card equivalent is ArtworkCard's badge). */
@Composable
fun AtmosChip() {
    val colors = MaterialTheme.colorScheme
    androidx.tv.material3.Text(
        "ATMOS",
        color = colors.onTertiaryContainer,
        fontSize = AuraTypography.caption,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(colors.tertiaryContainer, AuraShapes.badge)
            .padding(horizontal = AuraSpacing.xs, vertical = 2.dp)
    )
}

/** Loads (session-cached) the set of album ids containing an Atmos track. */
@Composable
fun rememberAtmosAlbumIds(
    api: com.hans.aura.data.JellyfinApi,
    userId: String
): Set<String> {
    var ids by remember { mutableStateOf<Set<String>>(emptySet()) }
    androidx.compose.runtime.LaunchedEffect(userId) {
        ids = runCatching {
            SourceQueries.atmosAlbumIds()
        }.getOrDefault(emptySet())
    }
    return ids
}
