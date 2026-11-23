@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.hans.aura.ui.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hans.aura.data.JellyfinItem
import com.hans.aura.ui.common.InitialFocusOnPush
import com.hans.aura.ui.common.OverlayVisibility
import com.hans.aura.ui.common.formatDuration
import com.hans.aura.ui.theme.AuraColors
import com.hans.aura.ui.theme.AuraShapes
import com.hans.aura.ui.theme.AuraSizes
import com.hans.aura.ui.theme.AuraSpacing
import com.hans.aura.ui.theme.AuraTypography
import com.hans.aura.ui.theme.Border
import com.hans.aura.ui.theme.FocusOutline
import com.hans.aura.ui.theme.SurfaceHigh
import com.hans.aura.ui.theme.TextPrimary
import com.hans.aura.ui.theme.TextSecondary

/**
 * Inline queue peek (B14): the next few tracks as a compact list over the
 * lower part of Now Playing.
 *
 * It is deliberately NOT the queue screen — the Queue button still opens the
 * full list — so it only offers two things: look ahead, and jump.
 *
 * Focus behaviour, which is the whole point on a D-pad-only remote:
 *  - the parent renders this as the LAST child of the screen-root Box, so it
 *    covers the transport row that opened it;
 *  - the first row claims focus ~100ms after composition (with retries,
 *    because a request before first layout silently does nothing);
 *  - focus is TRAPPED (exit = Cancel), so UP/DOWN can never land on an
 *    invisible control behind the panel;
 *  - the key that opened the peek is still held for a moment, so DOWN repeats
 *    and any OK release are swallowed for a grace window ([graceUntil]) —
 *    without that the first row got clicked by the same press.
 */
@Composable
internal fun QueuePeekOverlay(
    upcoming: List<JellyfinItem>,
    total: Int,
    accent: Color,
    focusToken: Int,
    graceUntil: Long,
    onExtend: () -> Unit,
    onCollapse: () -> Unit,
    onJump: (Int) -> Unit
) {
    // The app-level now-playing bar must not draw over the peek while it is up.
    OverlayVisibility.dialogOpen = true
    DisposableEffect(Unit) { onDispose { OverlayVisibility.dialogOpen = false } }

    val firstFocus = remember { FocusRequester() }
    var focusedRow by remember { mutableStateOf(0) }
    var pendingRow by remember { mutableStateOf<Int?>(null) }
    val rowFocus = remember(upcoming.size) {
        List(upcoming.size) { if (it == 0) firstFocus else FocusRequester() }
    }
    LaunchedEffect(upcoming.size, pendingRow) {
        val row = pendingRow ?: return@LaunchedEffect
        if (row < upcoming.size) {
            rowFocus[row].requestFocus()
            pendingRow = null
        }
    }
    // focusToken changes on every OPEN (not on extend), so extending does not
    // yank focus back to the top row.
    androidx.compose.runtime.key(focusToken) {
        InitialFocusOnPush(true, firstFocus, ready = upcoming.isNotEmpty())
    }

    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { e ->
                val stillHeld = android.os.SystemClock.uptimeMillis() < graceUntil
                when {
                    // The held DOWN (its repeats) and an OK release from the
                    // press that opened the peek must not drive the list.
                    stillHeld && (e.key == Key.DirectionDown ||
                        e.key == Key.DirectionCenter || e.key == Key.Enter) -> true
                    e.type != KeyEventType.KeyDown -> false
                    e.key == Key.DirectionDown && focusedRow == upcoming.lastIndex -> {
                        if (upcoming.size < total) {
                            pendingRow = focusedRow + 1
                            onExtend()
                        }
                        true
                    }
                    e.key == Key.DirectionUp && focusedRow == 0 -> { onCollapse(); true }
                    else -> false
                }
            }
    ) {
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = AuraSpacing.npHorizontal, bottom = AuraSpacing.screenHorizontal)
                .width(AuraSizes.peekWidth)
                .clip(AuraShapes.sheet)
                .background(AuraColors.PeekScrim)
                .border(AuraSizes.hairline, Border, AuraShapes.sheet)
                // Focus can never leave the panel while it is up.
                .focusProperties { onExit = { cancelFocusChange() } }
                .focusGroup()
                .padding(vertical = AuraSpacing.xs)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(
                        start = AuraSpacing.md,
                        end = AuraSpacing.md,
                        bottom = AuraSpacing.xxs
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.tv.material3.Text(
                    "Up next",
                    fontSize = AuraTypography.caption,
                    color = accent,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.weight(1f))
                androidx.tv.material3.Text(
                    if (upcoming.size > 3) "$total queued \u00B7 DOWN for more"
                    else "$total queued",
                    fontSize = AuraTypography.caption,
                    color = TextSecondary
                )
            }
            upcoming.forEachIndexed { i, track ->
                PeekRow(
                    track = track,
                    accent = accent,
                    modifier = Modifier.focusRequester(rowFocus[i]).focusProperties {
                        up = rowFocus.getOrNull(i - 1) ?: FocusRequester.Cancel
                        down = rowFocus.getOrNull(i + 1) ?: FocusRequester.Cancel
                    }.onFocusChanged {
                        if (it.isFocused) focusedRow = i
                    }
                ) {
                    onJump(i)
                }
            }
        }
    }
}

/**
 * One compact "up next" line: title, artist, duration. Focus is loud on
 * purpose (filled row + outline + accent bar) — the transport row behind it is
 * still visible and must never look like the focus target.
 */
@Composable
private fun PeekRow(
    track: JellyfinItem,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = AuraShapes.control
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = AuraSpacing.xs, vertical = AuraSpacing.xxs)
            .height(AuraSizes.peekRowHeight)
            .clip(shape)
            .background(if (focused) SurfaceHigh else Color.Transparent)
            .then(if (focused) Modifier.border(AuraSizes.focusBorder, FocusOutline, shape) else Modifier)
            // Before the focus target, as everywhere else in the app.
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClickLabel = "Jump to ${track.name}") { onClick() }
            .padding(horizontal = AuraSpacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(AuraSizes.accentBar)
                .height(AuraSpacing.lg)
                .background(
                    if (focused) accent else Color.Transparent,
                    AuraShapes.pill
                )
        )
        Spacer(Modifier.width(AuraSpacing.sm))
        Column(Modifier.weight(1f)) {
            androidx.tv.material3.Text(
                track.name,
                fontSize = AuraTypography.body,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            androidx.tv.material3.Text(
                track.artistNames.ifBlank { "Unknown artist" },
                fontSize = AuraTypography.caption,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(AuraSpacing.sm))
        androidx.tv.material3.Text(
            formatDuration(track.durationMs),
            fontSize = AuraTypography.caption,
            color = TextSecondary
        )
    }
}
