package com.hans.aura.playback

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.hans.aura.AuraApp
import com.hans.aura.data.APP_VERSION
import com.hans.aura.data.AuthStore
import com.hans.aura.data.JellyfinItem
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Owns the ExoPlayer instance. Direct play only: the container is fetched
 * progressively over HTTP and handed to the platform MediaCodec renderer,
 * which bitstreams E-AC-3 / E-AC-3 JOC (Dolby Digital Plus with Atmos) to
 * the HDMI sink when the AVR advertises support. Nothing on this pipeline
 * decodes to PCM, because PCM conversion strips the Atmos object metadata.
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var reporter: PlaybackReporter? = null
    private var remote: PlaybackRemote? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as AuraApp

        val player = buildPlayer(app)
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                reporter?.onError(error)
            }
        })

        mediaSession = MediaSession.Builder(this, player).build()
        reporter = PlaybackReporter(app.jellyfin, player).also { it.start() }
        // Insert remote items without interrupting playback; only start
        // playing if the queue was empty beforehand.
        fun enqueue(items: List<JellyfinItem>, at: Int) {
            val wasEmpty = player.mediaItemCount == 0
            val idx = if (wasEmpty) 0 else at
            items.forEachIndexed { i, item ->
                player.addMediaItem(idx + i, buildMediaItem(item))
            }
            if (wasEmpty) {
                player.prepare()
                player.playWhenReady = true
            }
        }
        remote = PlaybackRemote(app.jellyfin, player) { items, mode, startMs ->
            runOnMain {
                when (mode) {
                    PlaybackRemote.MODE_INSERT_NEXT ->
                        enqueue(items, player.currentMediaItemIndex + 1)
                    PlaybackRemote.MODE_APPEND_END ->
                        enqueue(items, player.mediaItemCount)
                    else -> {
                        player.setMediaItems(
                            items.map { buildMediaItem(it) },
                            0,
                            startMs.coerceAtLeast(0)
                        )
                        player.prepare()
                        player.playWhenReady = true
                    }
                }
            }
        }.also { it.start() }
    }

    private fun runOnMain(block: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(block)
    }

    private fun buildMediaItem(item: JellyfinItem): MediaItem {
        val container = item.mediaSources?.firstOrNull()?.container
        return MediaItem.Builder()
            .setMediaId(item.id)
            .setUri(AuthStore.audioStreamUrl((application as AuraApp).jellyfin.auth.session?.serverUrl ?: "", item.id, container))
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(item.name)
                    .setArtist(item.artistNames.ifBlank { "Unknown artist" })
                    .build()
            )
            .build()
    }

    private fun buildPlayer(app: AuraApp): ExoPlayer {
        val context = this

        // Direct-play HTTP source with auth headers.
        val session = app.jellyfin.auth.session
        val httpFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(
                mapOf(
                    "Authorization" to
                        "MediaBrowser Client=\"Aura\", Device=\"Android TV\", " +
                            "DeviceId=\"playback\", Version=\"$APP_VERSION\"" +
                            (session?.accessToken?.let { ", Token=\"$it\"" } ?: "")
                )
            )

        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                /* bufferForPlaybackMs = */ 2000,
                /* bufferForPlaybackAfterRebufferMs = */ 4000
            )
            .build()

        return ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        remote?.stop()
        remote = null
        reporter?.stop()
        reporter = null
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}

/**
 * Reports playback start/progress/stop to Jellyfin so server-side
 * play counts, "Recently Played" and resume positions stay accurate.
 */
internal class PlaybackReporter(
    private val jellyfin: com.hans.aura.data.JellyfinClient,
    private val player: Player
) {
    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob()
    )
    private var playSessionId: String = java.util.UUID.randomUUID().toString()
    private var currentItemId: String? = null
    private var handler: android.os.Handler? = null
    private var lastProgressAt = 0L
    private var lastPausedReported: Boolean? = null

    fun start() {
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        handler = h
        h.post(object : Runnable {
            override fun run() {
                val p = player
                val item = p.currentMediaItem
                val idle = p.playbackState == Player.STATE_IDLE ||
                    p.playbackState == Player.STATE_ENDED
                if (item != null && (p.isPlaying || (!idle && !p.playWhenReady))) {
                    // Playing, or parked on a prepared-but-paused track.
                    val id = item.mediaId
                    val paused = !p.isPlaying
                    val ticks = p.currentPosition.coerceAtLeast(0) * 10_000L
                    // Read the queue on this (main) thread — the player may not
                    // be touched from the report coroutines below.
                    val queue = queueSnapshot(p)
                    val repeatMode = repeatModeString(p)
                    val playbackOrder = if (p.shuffleModeEnabled) "Shuffle" else "Default"
                    if (id != currentItemId) {
                        android.util.Log.i("AuraReport", "start $id")
                        currentItemId?.let { oldId ->
                            scope.launch {
                                runCatching {
                                    jellyfin.api.reportPlaybackStopped(
                                        com.hans.aura.data.PlaybackStopInfo(
                                            itemId = oldId,
                                            playSessionId = playSessionId,
                                            positionTicks = ticks,
                                            mediaSourceId = oldId
                                        )
                                    )
                                }.onFailure {
                                    android.util.Log.w("AuraReport", "stop failed: ${it.message}")
                                }
                            }
                        }
                        currentItemId = id
                        playSessionId = java.util.UUID.randomUUID().toString()
                        lastProgressAt = android.os.SystemClock.elapsedRealtime()
                        scope.launch {
                            runCatching {
                                jellyfin.api.reportPlaybackStart(
                                    com.hans.aura.data.PlaybackStartInfo(
                                        itemId = id,
                                        playSessionId = playSessionId,
                                        canSeek = true,
                                        isPaused = paused,
                                        positionTicks = ticks,
                                        mediaSourceId = id,
                                        nowPlayingQueue = queue,
                                        repeatMode = repeatMode,
                                        playbackOrder = playbackOrder
                                    )
                                )
                            }.onFailure {
                                android.util.Log.w("AuraReport", "playbackStart failed: ${it.message}")
                            }
                        }
                    } else {
                        val now = android.os.SystemClock.elapsedRealtime()
                        // Push a progress report right away when paused state
                        // flips so the web remote's play/pause stays in sync.
                        val pausedChanged = lastPausedReported != paused
                        if (pausedChanged) lastProgressAt = 0L
                        if (now - lastProgressAt >= 10_000L) {
                            lastPausedReported = paused
                            lastProgressAt = now
                            scope.launch {
                                runCatching {
                                    jellyfin.api.reportPlaybackProgress(
                                        com.hans.aura.data.PlaybackProgressInfo(
                                            itemId = id,
                                            playSessionId = playSessionId,
                                            isPaused = paused,
                                            positionTicks = ticks,
                                            mediaSourceId = id,
                                            nowPlayingQueue = queue,
                                            repeatMode = repeatMode,
                                            playbackOrder = playbackOrder
                                        )
                                    )
                                }.onFailure {
                                    android.util.Log.w("AuraReport", "progress failed: ${it.message}")
                                }
                            }
                        }
                    }
                } else if (currentItemId != null && idle) {
                    // Track ended or playback was stopped (incl. remote Stop):
                    // clear the server session's NowPlayingItem.
                    val oldId = currentItemId!!
                    val ticks = p.currentPosition.coerceAtLeast(0) * 10_000L
                    android.util.Log.i("AuraReport", "session stopped $oldId")
                    currentItemId = null
                    scope.launch {
                        runCatching {
                            jellyfin.api.reportPlaybackStopped(
                                com.hans.aura.data.PlaybackStopInfo(
                                    itemId = oldId,
                                    playSessionId = playSessionId,
                                    positionTicks = ticks,
                                    mediaSourceId = oldId
                                )
                            )
                        }.onFailure {
                            android.util.Log.w("AuraReport", "stop failed: ${it.message}")
                        }
                    }
                }
                h.postDelayed(this, 1_000L)
            }
        })
    }

    private fun repeatModeString(p: Player): String = when (p.repeatMode) {
        Player.REPEAT_MODE_ONE -> "RepeatOne"
        Player.REPEAT_MODE_ALL -> "RepeatAll"
        else -> "RepeatNone"
    }

    /** The player's queue as server QueueItems (Id only) for the web remote. */
    private fun queueSnapshot(p: Player): List<com.hans.aura.data.QueueItem> =
        (0 until p.mediaItemCount).map { com.hans.aura.data.QueueItem(id = p.getMediaItemAt(it).mediaId) }

    fun onError(error: PlaybackException) {
        // Logged by ExoPlayer; surfaced in UI via the shared player state.
    }

    fun stop() {
        handler?.removeCallbacksAndMessages(null)
        handler = null
        scope.cancel()
    }
}
