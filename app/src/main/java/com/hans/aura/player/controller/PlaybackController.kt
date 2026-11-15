package com.hans.aura.player.controller

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.hans.aura.playback.PlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The Media3 [MediaController] half of PlayerConnection: connect, listen, and the
 * 500ms position ticker.
 *
 * Everything here touches Media3 on the main thread only (a MediaController call
 * from any other thread throws "Player is accessed on the wrong thread"). The
 * listener is forwarded to [Listener] so the facade owns the state flows and can
 * stay free of Media3 listener boilerplate.
 */
class PlaybackController(
    context: Context,
    private val scope: CoroutineScope
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

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs

    private var listener: Listener? = null

    /** Set once the owner releases this connection; never re-attach after that. */
    @Volatile
    private var released = false

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    /** Publishes a position the facade already computed (late resume). */
    fun publishPosition(ms: Long) {
        _positionMs.value = ms
    }

    fun connect() {
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val future = MediaController.Builder(appContext, token).buildAsync()
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

        // Position ticker: drives progress bars and lyric sync.
        scope.launch {
            while (true) {
                _controller.value?.let { c ->
                    _positionMs.value = c.currentPosition.coerceAtLeast(0)
                    _durationMs.value = c.duration.coerceAtLeast(0)
                }
                delay(TICK_MS)
            }
        }
    }

    private fun attach(c: MediaController) {
        val l = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                listener?.onMediaItemTransition(c, mediaItem?.mediaId)
                // The ticker only runs every 500ms; without this, positionMs
                // (and durationMs) still hold the previous track's values for
                // up to half a second after the item changes, which consumers
                // like the lyrics pane read as a stale time into the new song.
                _positionMs.value = 0
                _durationMs.value = 0
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                listener?.onIsPlayingChanged(isPlaying)
            }

            override fun onShuffleModeEnabledChanged(enabled: Boolean) {
                listener?.onShuffleModeEnabledChanged(enabled)
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                listener?.onRepeatModeChanged(repeatMode)
            }

            override fun onPlaybackStateChanged(state: Int) {
                listener?.onPlaybackStateChanged(c, state)
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                listener?.onTimelineChanged(c)
            }
        }
        c.addListener(l)

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
        _positionMs.value = 0L
        _durationMs.value = 0L
    }

    companion object {
        private const val TICK_MS = 500L
    }
}
