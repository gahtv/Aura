package com.hans.aura.player.controller

data class PlaybackAnchor(
    val positionMs: Long = 0L,
    val anchorUptimeMs: Long = 0L,
    val speed: Float = 1f,
    val playing: Boolean = false,
    val durationMs: Long = 0L
) {
    fun positionAt(uptimeMs: Long): Long {
        val position = if (playing) {
            (positionMs + (uptimeMs - anchorUptimeMs).toDouble() * speed).toLong()
        } else positionMs
        return position.coerceAtLeast(0L).let {
            if (durationMs > 0L) it.coerceAtMost(durationMs) else it
        }
    }
}
