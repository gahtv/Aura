package com.hans.aura.playback

import android.os.Handler
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

internal fun shouldReleasePausedAudioOutput(mimeType: String?): Boolean = when (mimeType) {
    "audio/eac3", "audio/eac3-joc", "audio/ac3", "audio/ac4",
    "audio/vnd.dts", "audio/vnd.dts.hd", "audio/vnd.dts.hd;profile=lbr",
    "audio/vnd.dts.uhd", "audio/vnd.dts.uhd;profile=p2", "audio/true-hd" -> true
    else -> false
}

internal class PausedAudioOutput(private val player: ExoPlayer) : Player.Listener {
    private val handler = Handler(player.applicationLooper)
    private var pendingRelease: Runnable? = null
    var released = false
        private set

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun shouldRelease(): Boolean =
        !player.playWhenReady && player.playbackState == Player.STATE_READY &&
            shouldReleasePausedAudioOutput(player.audioFormat?.sampleMimeType)

    override fun onEvents(player: Player, events: Player.Events) {
        if (player.playWhenReady || player.playbackState != Player.STATE_IDLE ||
            events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)
        ) {
            released = false
        }
        if (!shouldRelease()) {
            pendingRelease?.let { handler.removeCallbacks(it) }
            pendingRelease = null
        } else if (pendingRelease == null) {
            pendingRelease = Runnable {
                pendingRelease = null
                if (shouldRelease()) {
                    released = true
                    this.player.stop()
                }
            }.also { handler.postDelayed(it, 3_000L) }
        }
    }

    fun close() {
        handler.removeCallbacksAndMessages(null)
        player.removeListener(this)
    }
}

internal fun Player.playPrepared() {
    if (playbackState == Player.STATE_IDLE) prepare()
    play()
}
