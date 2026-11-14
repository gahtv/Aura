package com.hans.aura.ui.common

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * One-shot navigation requests raised by non-UI components (player, cache
 * helpers) that the app root consumes. Kept as a monotonically increasing
 * counter rather than a boolean so repeated requests each fire.
 */
object NavRequests {
    private val _openNowPlaying = MutableStateFlow(0)

    /** Ticked when something asks to show the Now Playing screen. */
    val openNowPlaying: MutableStateFlow<Int> = _openNowPlaying

    fun requestNowPlaying() {
        _openNowPlaying.value += 1
    }
}
