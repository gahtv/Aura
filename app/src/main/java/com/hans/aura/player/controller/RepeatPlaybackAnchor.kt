package com.hans.aura.player.controller

import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.session.SessionCommand

internal object RepeatPlaybackAnchor {
    val command = SessionCommand("com.hans.aura.REPEAT_PLAYBACK_ANCHOR", Bundle.EMPTY)

    fun snapshot(player: Player): Bundle = Bundle().apply {
        putLong("positionMs", player.currentPosition)
        putLong("anchorUptimeMs", SystemClock.uptimeMillis())
        putFloat("speed", player.playbackParameters.speed)
        putBoolean("playing", player.isPlaying)
        putLong("durationMs", player.duration.coerceAtLeast(0L))
    }

    fun read(args: Bundle): PlaybackAnchor? {
        if (!listOf("positionMs", "anchorUptimeMs", "speed", "playing", "durationMs")
                .all(args::containsKey)) return null
        return PlaybackAnchor(
            args.getLong("positionMs"),
            args.getLong("anchorUptimeMs"),
            args.getFloat("speed"),
            args.getBoolean("playing"),
            args.getLong("durationMs")
        )
    }
}
