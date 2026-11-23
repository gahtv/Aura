package com.hans.aura.player.controller

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.hans.aura.playback.PlaybackService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The Media3 [MediaController] half of PlayerConnection: connect and listen.
 *
 * Everything here touches Media3 on the main thread only (a MediaController call
 * from any other thread throws "Player is accessed on the wrong thread"). The
 * listener is forwarded to [Listener] so the facade owns the state flows and can
 * stay free of Media3 listener boilerplate.
 */
class PlaybackController(
    context: Context
) {

    /** Callbacks from the controller, always delivered on the main thread. */
    interface Listener {
        fun onAttached(c: MediaController)
        fun onMediaItemTransition(c: MediaController, mediaId: String?)
        fun onIsPlayingChanged(isPlaying: Boolean)
        fun onShuffleModeEnabledChanged(enabled: Boolean)
        fun onRepeatModeChanged(repeatMode: Int)
        fun onPlaybackStateChanged(c: MediaController, state: Int)
        fun onTimelineChanged(c: MediaController)
    }

    private val appContext = context.applicationContext

    private val _controller = MutableStateFlow<MediaController?>(null)
    val controller: StateFlow<MediaController?> = _controller

    private val _clock = MutableStateFlow(PlaybackAnchor())
    val clock: StateFlow<PlaybackAnchor> = _clock

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs

    private var listener: Listener? = null

    /** Set once the owner releases this connection; never re-attach after that. */
    @Volatile
    private var released = false

    private val sessionListener = object : MediaController.Listener {
        @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
        override fun onCustomCommand(
            controller: MediaController,
            command: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            if (command != RepeatPlaybackAnchor.command || released) {
                return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            val anchor = RepeatPlaybackAnchor.read(args)
                ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
            _clock.value = anchor
            _durationMs.value = anchor.durationMs
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    /** Publishes a position the facade already computed (late resume). */
    fun publishPosition(ms: Long) {
        _controller.value?.let { reanchor(it, ms) }
    }

    private fun reanchor(c: MediaController, positionMs: Long = c.currentPosition) {
        val duration = c.duration.coerceAtLeast(0L)
        _clock.value = PlaybackAnchor(
            positionMs, SystemClock.uptimeMillis(), c.playbackParameters.speed, c.isPlaying, duration
        )
        _durationMs.value = duration
    }

    fun connect() {
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val future = MediaController.Builder(appContext, token).setListener(sessionListener).buildAsync()
        future.addListener({
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            // A teardown/disconnect may land while the controller is still
            // connecting; never re-attach a controller after that.
            if (released) {
                runCatching { c.release() }
                return@addListener
            }
            _controller.value = c
            attach(c)
        }, ContextCompat.getMainExecutor(appContext))
    }

    private fun attach(c: MediaController) {
        val l = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                listener?.onMediaItemTransition(c, mediaItem?.mediaId)
                reanchor(c, 0L)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                reanchor(c)
                listener?.onIsPlayingChanged(isPlaying)
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                reanchor(c)
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                reanchor(c)
            }

            override fun onShuffleModeEnabledChanged(enabled: Boolean) {
                listener?.onShuffleModeEnabledChanged(enabled)
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                listener?.onRepeatModeChanged(repeatMode)
            }

            override fun onPlaybackStateChanged(state: Int) {
                reanchor(c)
                listener?.onPlaybackStateChanged(c, state)
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                reanchor(c)
                listener?.onTimelineChanged(c)
            }
        }
        c.addListener(l)
        reanchor(c)

        val sink = listener ?: return
        sink.onAttached(c)
        sink.onIsPlayingChanged(c.isPlaying)
    }

    /**
     * Releases the controller (that IS the disconnect) without touching the
     * player or the service: playback continues in PlaybackService for other
     * clients. Called from [com.hans.aura.ui.player.PlayerConnection.disconnect]
     * (Activity UI disposal) and from its teardown.
     */
    fun release() {
        released = true
        _controller.value?.let { c -> runCatching { c.release() } }
        _controller.value = null
        _clock.value = PlaybackAnchor(anchorUptimeMs = SystemClock.uptimeMillis())
        _durationMs.value = 0L
    }
}
