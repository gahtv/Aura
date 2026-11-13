@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.hans.aura.ui.queue

import androidx.compose.foundation.background
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.hans.aura.ui.common.TrackRow
import com.hans.aura.ui.player.PlayerConnection
import com.hans.aura.ui.theme.Accent
import com.hans.aura.ui.theme.Surface
import com.hans.aura.ui.theme.TextPrimary
import com.hans.aura.ui.theme.TextSecondary

/**
 * Play queue management: see what's lined up, jump to any track,
 * drop tracks you no longer want, toggle shuffle/repeat.
 */
@Composable
fun QueueScreen(player: PlayerConnection, onBack: () -> Unit, initialFocus: Boolean = false) {
    val queue by player.queue.collectAsState()
    val queueIndex by player.queueIndex.collectAsState()
    val shuffle by player.shuffleEnabled.collectAsState()
    val repeatMode by player.repeatMode.collectAsState()

    val contentFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    com.hans.aura.ui.common.InitialFocusOnPush(initialFocus, contentFocus)

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
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            androidx.tv.material3.Button(onClick = {
                player.setShuffle(!shuffle)
            }) {
                androidx.tv.material3.Text(
                    if (shuffle) "Shuffle on" else "Shuffle off",
                    color = if (shuffle) Accent else TextSecondary
                )
            }
            androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp))
            androidx.tv.material3.Button(onClick = {
                player.setRepeat(
                    if (repeatMode == Player.REPEAT_MODE_OFF) Player.REPEAT_MODE_ALL
                    else Player.REPEAT_MODE_OFF
                )
            }) {
                androidx.tv.material3.Text(
                    when (repeatMode) {
                        Player.REPEAT_MODE_ALL -> "Repeat all"
                        Player.REPEAT_MODE_ONE -> "Repeat one"
                        else -> "Repeat off"
                    },
                    color = if (repeatMode != Player.REPEAT_MODE_OFF) Accent else TextSecondary
                )
            }
            androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp))
            androidx.tv.material3.Button(onClick = onBack) {
                androidx.tv.material3.Text("Back")
            }
        }
        androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp))

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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                            TrackRow(
                                index = i + 1,
                                track = track,
                                isCurrent = i == queueIndex,
                                onClick = { player.skipToQueue(i) }
                            )
                        }
                        androidx.tv.material3.Button(
                            onClick = { player.removeFromQueue(i) },
                            modifier = Modifier
                                .padding(end = 24.dp)
                                .width(52.dp)
                        ) {
                            androidx.tv.material3.Text("\u2715", fontSize = 14.sp)
                        }
                    }
                }
                item {
                    androidx.compose.foundation.layout.Spacer(Modifier.height(60.dp))
                    androidx.tv.material3.Text(
                        "Queue ends here",
                        color = TextSecondary, fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                    androidx.compose.foundation.layout.Spacer(Modifier.height(60.dp))
                }
            }
        }
    }
}
