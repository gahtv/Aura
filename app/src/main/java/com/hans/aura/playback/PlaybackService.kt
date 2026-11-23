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
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.hans.aura.AuraApp
import com.hans.aura.BuildConfig
import com.hans.aura.data.APP_NAME
import com.hans.aura.data.APP_VERSION
import com.hans.aura.data.AuthStore
import com.hans.aura.data.JellyfinItem
import com.hans.aura.data.Session
import com.hans.aura.data.invalidateHistoryOnSuccess
import com.hans.aura.player.controller.RepeatPlaybackAnchor
import com.hans.aura.util.AuraLog
import com.hans.aura.util.AuraTag
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** A replayed track inside this window is not a second play. */
private const val PLAY_REPORT_INTERVAL_MS = 30L * 60L * 1000L

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
    private var pausedAudioOutput: PausedAudioOutput? = null

    /**
     * Backends that keep their own play history (the index) are told once per
     * track start. Reporting runs off the player thread: a slow or dead history
     * endpoint must never stall a transition.
     */
    private val playScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reportedPlays = java.util.concurrent.ConcurrentHashMap<String, Long>()

    override fun onCreate() {
        super.onCreate()
        val app = application as AuraApp

        val player = buildPlayer(app)
        val audioOutput = PausedAudioOutput(player)
        pausedAudioOutput = audioOutput
        player.addListener(audioOutput)
        // Unity gain, always: downmixing/attenuating in the player would apply
        // digital volume to the E-AC-3 JOC bitstream before the AVR sees it.
        // Level belongs to the receiver, so re-assert it on every track change
        // in case a platform path (audio focus duck) left it lowered.
        player.volume = 1f
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                reporter?.onError(error)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // Media3 1.6 deduplicates identical repeat transitions at the controller.
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                    mediaSession?.broadcastCustomCommand(
                        RepeatPlaybackAnchor.command, RepeatPlaybackAnchor.snapshot(player)
                    )
                }
                if (player.volume != 1f) {
                    AuraLog.i(AuraTag.VOL, "restoring unity gain (was ${player.volume})")
                    player.volume = 1f
                }
                // A track start is the play event. Jellyfin learns it from the session
                // reports; a backend with its own history has to be told.
                val source = app.container.source
                if (mediaItem == null || !source.hasPlayHistory) return
                val id = mediaItem.mediaId
                if (id.isBlank()) return
                val now = System.currentTimeMillis()
                val last = reportedPlays[id] ?: 0L
                // Repeat-one on a short track would otherwise write a play every few seconds.
                if (now - last < PLAY_REPORT_INTERVAL_MS) return
                reportedPlays[id] = now
                playScope.launch {
                    runCatching { source.recordPlay(id) }
                        .onFailure { AuraLog.w(AuraTag.PLAY, "play report failed id=$id") }
                }
            }
        })

        mediaSession = MediaSession.Builder(this, player).build()

        // Device-session side of playback: /Sessions/Playing* reports and the
        // /socket remote-control channel are Jellyfin server concepts. A source
        // without them gets neither, so nothing 404s on every tick and no
        // socket reconnect loop is ever started.
        if (app.container.source.reportsPlayState) {
            reporter = PlaybackReporter(app.jellyfin, player) { audioOutput.released }
                .also { it.start() }
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
            remote = PlaybackRemote(app.jellyfin, player) { items, mode, startMs, startIndex ->
                runOnMain {
                    when (mode) {
                        PlaybackRemote.MODE_INSERT_NEXT ->
                            enqueue(items, player.currentMediaItemIndex + 1)
                        PlaybackRemote.MODE_APPEND_END ->
                            enqueue(items, player.mediaItemCount)
                        else -> {
                            // Honour the sender's StartIndex: playing an album from
                            // Jellyfin web arrives as the whole track list plus the
                            // index of the chosen track.
                            player.setMediaItems(
                                items.map { buildMediaItem(it) },
                                startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
                                startMs.coerceAtLeast(0)
                            )
                            player.prepare()
                            player.playWhenReady = true
                        }
                    }
                }
            }.also { it.start() }
        }
    }

    private fun runOnMain(block: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(block)
    }

    private fun buildMediaItem(item: JellyfinItem): MediaItem {
        val app = application as AuraApp
        // The URI is the source's business, not the service's: a build can point
        // at a different backend without this file changing.
        val uri = app.container.source.streamUri(
            itemId = item.id,
            container = item.mediaSources?.firstOrNull()?.container,
            serverUrl = app.jellyfin.auth.session?.serverUrl
        )
        return MediaItem.Builder()
            .setMediaId(item.id)
            .setUri(uri)
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

        // Direct-play HTTP source. The Authorization header is resolved per
        // DataSource creation, not once at service construction: a server switch
        // or a re-sign-in must never stream with the previous token/host.
        val httpFactory = AuthDataSourceFactory(
            sessionProvider = { app.jellyfin.auth.session },
            deviceIdProvider = { app.jellyfin.deviceId }
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
        pausedAudioOutput?.close()
        pausedAudioOutput = null
        playScope.cancel()
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
 * DataSource factory that resolves the Jellyfin Authorization header EVERY time
 * a data source is created.
 *
 * The old code baked one header into the factory at service construction, so a
 * change of server or a re-sign-in kept streaming with the previous token and
 * the previous device id ("playback", which was not even this install's).
 * Reading session + device id lazily costs nothing and cannot go stale.
 */
private class AuthDataSourceFactory(
    private val sessionProvider: () -> Session?,
    private val deviceIdProvider: () -> String
) : DataSource.Factory {

    // media3 marks the header-setter as unstable; the surface is unchanged in 1.6.
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun createDataSource(): DataSource =
        DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(
                mapOf(
                    "Authorization" to AuthStore.authHeader(
                        sessionProvider(),
                        deviceIdProvider(),
                        APP_NAME,
                        BuildConfig.VERSION_NAME
                    )
                )
            )
            .createDataSource()
}

/**
 * Reports playback start/progress/stop to Jellyfin so server-side
 * play counts, "Recently Played" and resume positions stay accurate.
 */
internal class PlaybackReporter(
    private val jellyfin: com.hans.aura.data.JellyfinClient,
    private val player: Player,
    private val scope: kotlinx.coroutines.CoroutineScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob()
    ),
    private val isOutputReleased: () -> Boolean = { false }
) {
    private var playSessionId: String = java.util.UUID.randomUUID().toString()
    private var currentItemId: String? = null
    private var handler: android.os.Handler? = null
    private var lastProgressAt = 0L
    private var lastPausedReported: Boolean? = null
    /** Last position seen for [currentItemId] — the stop report needs it. */
    private var lastTicksForCurrent = 0L

    fun start() {
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        handler = h
        h.post(object : Runnable {
            override fun run() {
                val p = player
                val item = p.currentMediaItem
                val idle = (p.playbackState == Player.STATE_IDLE && !isOutputReleased()) ||
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
                        AuraLog.i(AuraTag.REPORT, "start $id")
                        currentItemId?.let { oldId ->
                            // The position belongs to the OLD item: `ticks` here
                            // is read from the player AFTER it switched to the
                            // new track (0:00), which saved every skipped track
                            // at position 0 and made resume-where-you-left-off
                            // impossible. Use the last position seen for it.
                            val oldTicks = lastTicksForCurrent.coerceAtLeast(0)
                            // Capture the OLD play session id on THIS (main)
                            // tick: `playSessionId` is reassigned below, and the
                            // coroutine on Dispatchers.IO reads the field later —
                            // it raced and stopped the NEW session instead of the
                            // one being replaced.
                            val oldSessionId = playSessionId
                            com.hans.aura.data.ResumePoints.save(oldId, oldTicks / 10_000L)
                            AuraLog.i(AuraTag.REPORT, "switch stop old=$oldId at=$oldTicks")
                            scope.launch {
                                runCatching {
                                    jellyfin.api.reportPlaybackStopped(
                                        com.hans.aura.data.PlaybackStopInfo(
                                            itemId = oldId,
                                            playSessionId = oldSessionId,
                                            positionTicks = oldTicks,
                                            mediaSourceId = oldId
                                        )
                                    ).invalidateHistoryOnSuccess(oldId)
                                }.onFailure {
                                    AuraLog.w(AuraTag.REPORT, "stop failed: ${it.message}")
                                }
                            }
                        }
                        currentItemId = id
                        lastTicksForCurrent = ticks
                        playSessionId = java.util.UUID.randomUUID().toString()
                        // Same capture rule for the start report below.
                        val newSessionId = playSessionId
                        lastProgressAt = android.os.SystemClock.elapsedRealtime()
                        scope.launch {
                            runCatching {
                                jellyfin.api.reportPlaybackStart(
                                    com.hans.aura.data.PlaybackStartInfo(
                                        itemId = id,
                                        playSessionId = newSessionId,
                                        canSeek = true,
                                        isPaused = paused,
                                        positionTicks = ticks,
                                        mediaSourceId = id,
                                        nowPlayingQueue = queue,
                                        repeatMode = repeatMode,
                                        playbackOrder = playbackOrder
                                    )
                                ).invalidateHistoryOnSuccess(id)
                            }.onFailure {
                                AuraLog.w(AuraTag.REPORT, "playbackStart failed: ${it.message}")
                            }
                        }
                    } else {
                        val now = android.os.SystemClock.elapsedRealtime()
                        // Push a progress report right away when paused state
                        // flips so the web remote's play/pause stays in sync.
                        lastTicksForCurrent = ticks
                        // Remember where this track is for resume-where-you-left-
                        // off: the server does not store it (Jellyfin 12.2.0
                        // ignores PositionTicks on the report endpoints).
                        com.hans.aura.data.ResumePoints.save(id, p.currentPosition)
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
                                    AuraLog.w(AuraTag.REPORT, "progress failed: ${it.message}")
                                }
                            }
                        }
                    }
                } else if (currentItemId != null && idle) {
                    // Track ended or playback was stopped (incl. remote Stop):
                    // clear the server session's NowPlayingItem.
                    currentItemId?.let { oldId ->
                        val ticks = p.currentPosition.coerceAtLeast(0) * 10_000L
                        // Capture before the coroutine reads it off-thread.
                        val sessionId = playSessionId
                        AuraLog.i(AuraTag.REPORT, "session stopped $oldId")
                        currentItemId = null
                        scope.launch {
                            runCatching {
                                jellyfin.api.reportPlaybackStopped(
                                    com.hans.aura.data.PlaybackStopInfo(
                                        itemId = oldId,
                                        playSessionId = sessionId,
                                        positionTicks = ticks,
                                        mediaSourceId = oldId
                                    )
                                ).invalidateHistoryOnSuccess(oldId)
                            }.onFailure {
                                AuraLog.w(AuraTag.REPORT, "stop failed: ${it.message}")
                            }
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
