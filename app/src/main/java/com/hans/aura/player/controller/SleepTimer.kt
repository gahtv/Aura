package com.hans.aura.player.controller

import com.hans.aura.util.AuraLog
import com.hans.aura.util.AuraTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Sleep timer: pause playback after a number of seconds.
 *
 * On expiry the player is PAUSED — never stopped, never unqueued — so the user
 * can pick up where the timer cut in. The countdown itself lives here; the pause
 * is delivered through [onExpire] on the scope's dispatcher (the facade marshal-
 * les it to the main thread for Media3).
 */
class SleepTimer(
    private val scope: CoroutineScope,
    private val onExpire: () -> Unit
) {
    private val _remaining = MutableStateFlow<Long?>(null)

    /** Milliseconds left on the sleep timer, null when no timer is running. */
    val remaining: StateFlow<Long?> = _remaining

    private var job: Job? = null

    /** Pause playback after [seconds] (UI passes minutes * 60; tests pass 5). */
    fun start(seconds: Int) {
        if (seconds <= 0) {
            cancel()
            return
        }
        job?.cancel()
        AuraLog.d(AuraTag.SLEEP, "start=$seconds")
        _remaining.value = seconds * 1000L
        job = scope.launch {
            var left = seconds * 1000L
            while (left > 0) {
                delay(TICK_MS)
                left = (left - TICK_MS).coerceAtLeast(0)
                _remaining.value = left
            }
            _remaining.value = null
            job = null
            onExpire()
            AuraLog.d(AuraTag.SLEEP, "fired")
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        val wasRunning = _remaining.value != null
        _remaining.value = null
        if (wasRunning) AuraLog.d(AuraTag.SLEEP, "cancel")
    }

    /** Teardown: drop the countdown without logging a user-facing cancel. */
    fun clear() {
        job?.cancel()
        job = null
        _remaining.value = null
    }

    companion object {
        private const val TICK_MS = 1_000L
    }
}
