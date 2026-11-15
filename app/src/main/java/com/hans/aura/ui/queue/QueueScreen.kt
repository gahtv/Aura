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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.hans.aura.ui.common.ContextMenuHost
import com.hans.aura.ui.common.ContextMenus
import com.hans.aura.ui.common.InitialFocusOnPush
import com.hans.aura.ui.common.TrackRow
import com.hans.aura.ui.player.PlayerConnection
import com.hans.aura.ui.theme.Accent
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
    val shuffle by player.shuffleEnabled.collectAsState()
    val repeatMode by player.repeatMode.collectAsState()
    val smart by player.smartShuffle.collectAsState()

    val contentFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    InitialFocusOnPush(initialFocus, contentFocus)

    val upcoming = (queue.size - (queueIndex + 1)).coerceAtLeast(0)

    Column(
        Modifier
            .fillMaxSize()
            .background(Surface)
            .padding(horizontal = 48.dp, vertical = 28.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.tv.material3.Text(
                "Queue \u00B7 ${queue.size} tracks",
                fontSize = 28.sp, color = TextPrimary
            )
            Spacer(Modifier.weight(1f))
            HeaderActionButton(
                label = if (shuffle) "Shuffle on" else "Shuffle off",
                active = shuffle,
                onClick = { player.setShuffle(!shuffle) }
            )
            // Smart shuffle: least-played / longest-unplayed first, reversible.
            Spacer(Modifier.width(12.dp))
            HeaderActionButton(
                label = if (smart) "Normal order" else "Smart shuffle",
                active = smart,
                onClick = {
                    if (smart) player.restoreNormalOrder() else player.applySmartOrder()
                }
            )
            Spacer(Modifier.width(12.dp))
            HeaderActionButton(
                label = when (repeatMode) {
                    Player.REPEAT_MODE_ALL -> "Repeat all"
                    Player.REPEAT_MODE_ONE -> "Repeat one"
                    else -> "Repeat off"
                },
                active = repeatMode != Player.REPEAT_MODE_OFF,
                onClick = {
                    player.setRepeat(
                        if (repeatMode == Player.REPEAT_MODE_OFF) Player.REPEAT_MODE_ALL
                        else Player.REPEAT_MODE_OFF
                    )
                }
            )
            // Only meaningful while something is actually queued behind the
            // current track; the current track keeps playing either way.
            if (upcoming > 0) {
                Spacer(Modifier.width(12.dp))
                HeaderActionButton(
                    label = "Clear upcoming ($upcoming)",
                    onClick = { player.clearUpcoming() }
                )
            }
            Spacer(Modifier.width(12.dp))
            HeaderActionButton(label = "Back", onClick = onBack)
        }
        Spacer(Modifier.height(16.dp))

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
                itemsIndexed(queue) { i, track ->
                    TrackRow(
                        index = i + 1,
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
                                    track = track
                                )
                            )
                        }
                    )
                }
                item {
                    Spacer(Modifier.height(60.dp))
                    androidx.tv.material3.Text(
                        "Queue ends here",
                        color = TextSecondary, fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                    Spacer(Modifier.height(120.dp)) // room for the now-playing bar
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
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .height(44.dp)
            .clip(shape)
            .background(if (focused) SurfaceHigh else Color.Transparent)
            .then(if (focused) Modifier.border(2.dp, FocusOutline, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() }
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        androidx.tv.material3.Text(
            label,
            fontSize = 15.sp,
            color = when {
                active -> Accent
                focused -> TextPrimary
                else -> TextSecondary
            }
        )
    }
}
