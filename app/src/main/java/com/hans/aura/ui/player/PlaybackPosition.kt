package com.hans.aura.ui.player

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis

/** Playback position that frame-ticks only while the anchor is playing. */
@Composable
fun rememberPlaybackPosition(player: PlayerConnection): State<Long> {
    val anchor by player.clock.collectAsState()
    val position = remember(anchor) { mutableLongStateOf(anchor.positionAt(SystemClock.uptimeMillis())) }
    LaunchedEffect(anchor) {
        if (anchor.playing) {
            while (true) {
                withFrameMillis { position.longValue = anchor.positionAt(it) }
            }
        }
    }
    return position
}
