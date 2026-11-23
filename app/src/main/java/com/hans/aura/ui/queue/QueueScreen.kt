@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.hans.aura.ui.queue

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.tv.material3.MaterialTheme
import com.hans.aura.ui.common.ConnectedAction
import com.hans.aura.ui.common.ConnectedActions
import com.hans.aura.ui.common.ContextMenuHost
import com.hans.aura.ui.common.ContextMenus
import com.hans.aura.ui.common.InitialFocusOnPush
import com.hans.aura.ui.common.TrackRow
import com.hans.aura.ui.player.PlayerConnection
import com.hans.aura.ui.player.nextRepeatMode
import com.hans.aura.ui.theme.Accent
import com.hans.aura.ui.theme.AuraShapes
import com.hans.aura.ui.theme.AuraSizes
import com.hans.aura.ui.theme.AuraSpacing
import com.hans.aura.ui.theme.AuraTypography
import com.hans.aura.ui.theme.FocusOutline
import com.hans.aura.ui.theme.Surface
import com.hans.aura.ui.theme.SurfaceHigh
import com.hans.aura.ui.theme.TextPrimary
import com.hans.aura.ui.theme.TextSecondary

/**
 * Play queue management: see what's lined up, jump to any track, reorder or
 * drop tracks, toggle shuffle/repeat.
 *
 * Reordering is MENU-DRIVEN on purpose — a TV has no pointer, so a drag gesture
 * cannot exist. Hold OK (~500ms) on a row for Move up / Move down / Move to top
 * / Remove. The menu itself is the shared ContextMenuOverlay hosted by AppRoot
 * (focus trapping, OK grace window, OverlayVisibility all live there).
 */
@Composable
fun QueueScreen(player: PlayerConnection, onBack: () -> Unit, initialFocus: Boolean = false) {
    val queue by player.queue.collectAsState()
    val queueIndex by player.queueIndex.collectAsState()
    val displayQueue by player.playOrder.collectAsState()
    val queueReady by player.queueReady.collectAsState()
    val shuffle by player.shuffleEnabled.collectAsState()
    val repeatMode by player.repeatMode.collectAsState()
    val smart by player.smartShuffle.collectAsState()

    val contentFocus = remember { FocusRequester() }
    InitialFocusOnPush(initialFocus, contentFocus,
        ready = displayQueue.isNotEmpty(), cancelled = queueReady && displayQueue.isEmpty())

    val upcoming = (queue.size - (queueIndex + 1)).coerceAtLeast(0)

    Column(
        Modifier
            .fillMaxSize()
            .background(Surface)
            .padding(
                horizontal = AuraSpacing.xxl,
                vertical = AuraSpacing.queueHeaderVertical
            )
    ) {
        // Budget note: the title + seven actions must fit 960dp of TV width, so
        // this row keeps the compact 38dp / 13sp / 11dp metrics from AuraSizes.
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.tv.material3.Text(
                "Queue \u00B7 ${queue.size}",
                style = MaterialTheme.typography.headlineSmall, color = TextPrimary
            )
            Spacer(Modifier.weight(1f))
            ConnectedActions(
                actions = listOf(
                    ConnectedAction(
                        label = if (shuffle) "Shuffle on" else "Shuffle off",
                        active = shuffle,
                        onClick = { player.setShuffle(!shuffle) }
                    ),
                    ConnectedAction(
                        label = if (smart) "Normal order" else "Smart",
                        active = smart,
                        onClick = {
                            if (smart) player.restoreNormalOrder() else player.applySmartOrder()
                        }
                    ),
                    ConnectedAction(
                        label = when (repeatMode) {
                            Player.REPEAT_MODE_ALL -> "Repeat all"
                            Player.REPEAT_MODE_ONE -> "Repeat one"
                            else -> "Repeat off"
                        },
                        active = repeatMode != Player.REPEAT_MODE_OFF,
                        onClick = { player.setRepeat(nextRepeatMode(repeatMode)) }
                    )
                ),
                compact = true
            )
            // Only meaningful while something is actually queued behind the
            // current track; the current track keeps playing either way.
            if (upcoming > 0) {
                Spacer(Modifier.width(AuraSpacing.xs))
                HeaderActionButton(
                    label = "Clear ($upcoming)",
                    onClick = { player.clearUpcoming() }
                )
            }
            Spacer(Modifier.width(AuraSpacing.xs))
            // Sleep timer lives on the QUEUE screen: that is the screen you are
            // on when you decide how much longer the music should run, and Now
            // Playing stays transport-only.
            SleepChip(player)
            Spacer(Modifier.width(AuraSpacing.sm))
            HeaderActionButton(label = "Back", onClick = onBack)
        }
        Spacer(Modifier.height(AuraSpacing.md))

        if (queue.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                androidx.tv.material3.Text("Queue is empty", color = TextSecondary)
            }
        } else {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .focusRequester(contentFocus)
                    .focusRestorer()
            ) {
                itemsIndexed(displayQueue) { position, entry ->
                    val (i, track) = entry
                    TrackRow(
                        index = position + 1,
                        track = track,
                        isCurrent = i == queueIndex,
                        onClick = { player.skipToQueue(i) },
                        onLongPress = {
                            ContextMenuHost.open(
                                ContextMenus.forQueueRow(
                                    player = player,
                                    index = i,
                                    size = queue.size,
                                    currentIndex = queueIndex,
                                    track = track,
                                    reorderable = !shuffle
                                )
                            )
                        }
                    )
                }
                item {
                    Spacer(Modifier.height(AuraSizes.minTarget))
                    androidx.tv.material3.Text(
                        "Queue ends here",
                        color = TextSecondary, fontSize = AuraTypography.caption,
                        modifier = Modifier.padding(horizontal = AuraSpacing.lg)
                    )
                    // Room for the now-playing bar.
                    Spacer(Modifier.height(AuraSizes.bottomBarClearance))
                }
            }
        }
    }
}

/**
 * Header button with the project's loud TV focus state: grey ring + lifted fill
 * on focus, amber text for an "on" state. `.onFocusChanged` MUST come before
 * `.clickable` or the flag never flips and the focus treatment silently never
 * renders.
 */
@Composable
private fun HeaderActionButton(
    label: String,
    active: Boolean = false,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = AuraShapes.control
    Box(
        Modifier
            // 38dp tall and 11dp of side padding: seven actions plus the title
            // have to fit 960dp of TV width, and the sleep chip was the one that
            // overflowed the row.
            .height(AuraSizes.compactHeaderActionHeight)
            .clip(shape)
            .background(if (focused) SurfaceHigh else Color.Transparent)
            .then(if (focused) Modifier.border(AuraSizes.focusBorder, FocusOutline, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() }
            .padding(horizontal = AuraSizes.compactHeaderActionPadding),
        contentAlignment = Alignment.Center
    ) {
        androidx.tv.material3.Text(
            label,
            fontSize = AuraTypography.label,
            color = when {
                active -> Accent
                focused -> TextPrimary
                else -> TextSecondary
            }
        )
    }
}

/**
 * Sleep timer: Off -> 15 -> 30 -> 45 -> 60 minutes, showing the time left while
 * it runs. Expiry PAUSES playback and leaves the queue alone (a sleep timer is
 * not a stop button), which is why it lives here rather than on Now Playing.
 */
@Composable
private fun SleepChip(player: PlayerConnection) {
    val remaining by player.sleepRemaining.collectAsState()
    // 0 = off, 1..4 = the preset index currently armed.
    var step by remember { mutableStateOf(0) }
    val presets = listOf(15, 30, 45, 60)
    // A fired/cancelled timer (remaining -> null) puts the chip back to Off,
    // including when the Settings row armed it.
    androidx.compose.runtime.LaunchedEffect(remaining != null) {
        if (remaining == null) step = 0
    }
    val remainingMs = remaining
    val label = when {
        remainingMs != null -> "Sleep " + sleepClock(remainingMs)
        step in 1..4 -> "Sleep ${presets[step - 1]} min"
        else -> "Sleep off"
    }
    HeaderActionButton(label = label, active = remainingMs != null) {
        step = (step + 1) % (presets.size + 1)
        if (step == 0) {
            player.cancelSleepTimer()
        } else {
            player.startSleepTimer(presets[step - 1] * 60)
        }
    }
}

/** mm:ss left on the sleep timer. */
private fun sleepClock(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
