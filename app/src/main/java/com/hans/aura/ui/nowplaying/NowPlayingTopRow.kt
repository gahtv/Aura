@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.hans.aura.ui.nowplaying

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import com.hans.aura.data.JellyfinItem
import com.hans.aura.ui.common.ContextMenuHost
import com.hans.aura.ui.player.PlayerConnection.Companion.isAtmos
import com.hans.aura.ui.theme.AuraColors
import com.hans.aura.ui.theme.AuraShapes
import com.hans.aura.ui.theme.AuraSizes
import com.hans.aura.ui.theme.AuraSpacing
import com.hans.aura.ui.theme.AuraTypography
import com.hans.aura.ui.theme.LocalDynamicTheme
import com.hans.aura.ui.theme.TextPrimary
import com.hans.aura.ui.theme.TextSecondary

/**
 * Now Playing chrome above the fold: Back on the left, Atmos badge centred,
 * then Queue / Add to playlist / Save queue / Lyrics(Track) on the right.
 *
 * [visible] is driven by the lyrics auto-hide. [lyricsMode] switches the
 * last button's label and insets the row itself (the lyrics branch has no
 * Column padding).
 */
@Composable
internal fun NowPlayingTopRow(
    visible: Boolean,
    lyricsMode: Boolean,
    item: JellyfinItem?,
    queue: List<JellyfinItem>,
    onBack: () -> Unit,
    onOpenQueue: () -> Unit,
    onFindLyrics: (() -> Unit)?,
    onToggleLyrics: () -> Unit
) {
    if (!visible) return
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                // Lyrics view has no Column padding; inset the controls row itself.
                if (lyricsMode) {
                    Modifier.padding(
                        start = AuraSpacing.npHorizontal,
                        end = AuraSpacing.npHorizontal,
                        top = AuraSpacing.xl
                    )
                } else Modifier
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NpControlButton(label = "Back", onClick = onBack)
        Spacer(Modifier.weight(1f))
        if (isAtmos(item)) AtmosBadge()
        Spacer(Modifier.weight(1f))
        NpControlButton(label = "Queue", onClick = onOpenQueue)
        Spacer(Modifier.width(AuraSpacing.sm))
        NpControlButton(
            label = "Add to playlist",
            enabled = item != null,
            onClick = { item?.let { ContextMenuHost.openPicker(listOf(it)) } }
        )
        Spacer(Modifier.width(AuraSpacing.sm))
        NpControlButton(
            label = "Save queue as playlist",
            enabled = queue.isNotEmpty(),
            onClick = { ContextMenuHost.openPicker(queue) }
        )
        Spacer(Modifier.width(AuraSpacing.sm))
        if (onFindLyrics != null) {
            NpControlButton(label = "Find lyrics", onClick = onFindLyrics)
            Spacer(Modifier.width(AuraSpacing.sm))
        }
        NpControlButton(
            label = if (lyricsMode) "Track" else "Lyrics",
            onClick = onToggleLyrics
        )
        Spacer(Modifier.width(AuraSpacing.sm))
    }
    Spacer(Modifier.height(AuraSpacing.lg))
}

@Composable
fun AtmosBadge() {
    val colors = MaterialTheme.colorScheme
    val dynamic = LocalDynamicTheme.current
    Box(
        Modifier
            .background(if (dynamic) colors.tertiary else colors.tertiary.copy(alpha = 0.20f), AuraShapes.badge)
            .padding(horizontal = AuraSpacing.sm, vertical = AuraSpacing.xxs)
    ) {
        androidx.tv.material3.Text(
            "DD+ \u2022 ATMOS",
            color = if (dynamic) colors.onTertiary else colors.tertiary,
            fontSize = AuraTypography.caption,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * Top-row control on Now Playing. The tv-material Button's focused state is a
 * barely-visible tint on a dark screen, so these draw the app's cursor language
 * explicitly: light grey outline + lifted fill, and the border thickens the
 * moment focus lands.
 */
@Composable
internal fun NpControlButton(
    label: String,
    enabled: Boolean = true,
    active: Boolean = false,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    val shape = AuraShapes.control
    val bg by animateColorAsState(
        when {
            !enabled -> AuraColors.GlassFillMuted
            focused -> AuraColors.FocusedFill
            else -> AuraColors.PillBackgroundRaised
        },
        label = "npControlBg"
    )
    Box(
        Modifier
            .clip(shape)
            .background(bg)
            .border(
                width = if (focused) AuraSizes.focusBorderLoud else 0.dp,
                color = if (focused) AuraColors.CursorRing else Color.Transparent,
                shape = shape
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(enabled = enabled, onClickLabel = label) { onClick() }
            .padding(horizontal = AuraSpacing.md, vertical = AuraSpacing.sm)
    ) {
        androidx.tv.material3.Text(
            label,
            color = when {
                !enabled -> TextSecondary
                active -> colors.primary
                else -> TextPrimary
            },
            fontSize = AuraTypography.bodySmall,
            fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}
