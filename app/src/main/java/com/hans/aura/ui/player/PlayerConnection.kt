package com.hans.aura.ui.player

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.hans.aura.data.JellyfinClient
import com.hans.aura.model.Track
import com.hans.aura.data.Session
import com.hans.aura.data.SourceQueries
import com.hans.aura.playback.PlaybackService
import com.hans.aura.player.controller.AutoplayPolicy
import com.hans.aura.player.controller.PlaybackAnchor
import com.hans.aura.player.controller.PlaybackController
import com.hans.aura.player.controller.QueueItemLoader
import com.hans.aura.player.controller.QueueRegistry
import com.hans.aura.player.controller.ResumePolicy
import com.hans.aura.player.controller.SleepTimer
import com.hans.aura.util.AuraLog
import com.hans.aura.util.AuraTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Single shared connection to the playback session, exposed as state flows.
 *
 * This class is the stable, Compose-facing API (the `ui` package depends on
 * StateFlows and command methods keep their signatures, and the work is split
 * into focused helpers under `com.hans.aura.player.controller`:
 *
 *  - [QueueRegistry]      mediaId -> item registry, queue-id snapshot, smart order
 *  - [ResumePolicy]       "resume where you left off" thresholds
 *  - [AutoplayPolicy]     Instant Mix continuation when the queue ends
 *  - [SleepTimer]         pause-after-N-minutes countdown
 *  - [PlaybackController] the MediaController connection, listener and ticker
 *
 * MediaItems carry only mediaId + URI (media3 1.6 exposes no extras payload);
 * the full Track is kept in the registry. The stream URI never embeds the
 * token — the playback service's DataSource factory adds the Authorization
 * header to every request.
 */
class PlayerConnection(
    context: Context,
    private val jellyfin: JellyfinClient,
    private val sessionProvider: () -> Session?
) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** mediaId -> full item (UI metadata, Atmos badge, lyrics, reporting). */
    private val registry = QueueRegistry()
    private val queueItemLoader = QueueItemLoader(scope, registry)

    private val resumePolicy = ResumePolicy()

    private val playback = PlaybackController(context)

    private val autoplay = AutoplayPolicy(
        jellyfin = jellyfin,
        sessionProvider = sessionProvider,
        registry = registry,
        scope = scope
    ) { block -> onMainThread(block) }

    private val sleep = SleepTimer(scope) {
        // Pause only: never stop, never unqueue — the user picks up where the
        // timer cut in. Media3 must be touched on the main thread.
        onMainThread { playback.controller.value?.pause() }
    }

    /** Set by [teardown]/[disconnect]: never re-attach after the connection closes. */
    @Volatile
    private var closed = false

    private val _currentItem = MutableStateFlow<Track?>(null)
    private val _isPlaying = MutableStateFlow(false)
    private val _queue = MutableStateFlow<List<Track>>(emptyList())
    private val _queueReady = MutableStateFlow(false)
    private val _queueIndex = MutableStateFlow(0)
    private val _upcoming = MutableStateFlow<List<IndexedValue<Track>>>(emptyList())
    private val _playOrder = MutableStateFlow<List<IndexedValue<Track>>>(emptyList())
    private val _shuffleEnabled = MutableStateFlow(false)
    private val _repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF)
    private val _smartShuffle = MutableStateFlow(false)

    /** True while the queue's upcoming items are in smart (least-played) order. */
    val smartShuffle: StateFlow<Boolean> = _smartShuffle

    val current: StateFlow<Track?> = _currentItem
    val isPlaying: StateFlow<Boolean> = _isPlaying
    val clock: StateFlow<PlaybackAnchor> = playback.clock
    val durationMs: StateFlow<Long> = playback.durationMs
    val queue: StateFlow<List<Track>> = _queue
    val queueReady: StateFlow<Boolean> = _queueReady
    val queueIndex: StateFlow<Int> = _queueIndex
    val upcoming: StateFlow<List<IndexedValue<Track>>> = _upcoming
    val playOrder: StateFlow<List<IndexedValue<Track>>> = _playOrder
    val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled
    val repeatMode: StateFlow<Int> = _repeatMode

    internal fun currentPositionMs(): Long =
        clock.value.positionAt(SystemClock.uptimeMillis())

    /** Milliseconds left on the sleep timer, null when no timer is running. */
    val sleepRemaining: StateFlow<Long?> = sleep.remaining

    init {
        playback.setListener(object : PlaybackController.Listener {
            override fun onAttached(c: MediaController) {
                syncItem(c, c.currentMediaItem?.mediaId)
                readQueue(c)
            }

            override fun onMediaItemTransition(c: MediaController, mediaId: String?) {
                syncItem(c, mediaId)
                _queueIndex.value = c.currentMediaItemIndex
                readUpcoming(c)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
            }

            override fun onShuffleModeEnabledChanged(enabled: Boolean) {
                _shuffleEnabled.value = enabled
                controller()?.let { readUpcoming(it) }
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                _repeatMode.value = repeatMode
                controller()?.let { readUpcoming(it) }
            }

            override fun onPlaybackStateChanged(c: MediaController, state: Int) {
                readQueue(c)
                // Queue ran out at the last track: keep the music going.
                if (state == Player.STATE_ENDED) autoplay.maybeContinue(c)
            }

            override fun onTimelineChanged(c: MediaController) {
                // Remote Play Next/Last mutate the playlist without a playback
                // state change — keep the queue screen in sync.
                readQueue(c)
            }
        })
        playback.connect()
    }

    private fun controller(): MediaController? = playback.controller.value

    /** Resolve a mediaId to a Track, fetching from the server when the
     *  item wasn't queued by this UI (e.g. remote "Play On" casts). */
    private fun syncItem(c: MediaController, mediaId: String?) {
        if (mediaId == null) {
            _currentItem.value = null
            return
        }
        val known = registry.get(mediaId)
        if (known != null) {
            _currentItem.value = known
        } else {
            _currentItem.value = null
            scope.launch {
                val item = try {
                    queueItemLoader.load(mediaId) {
                        SourceQueries.item(mediaId) as? Track ?: error("Track not found: $mediaId")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    return@launch
                }
                // Only publish if this is still the current item.
                if (c.currentMediaItem?.mediaId == mediaId) {
                    _currentItem.value = item
                }
                readQueue(c)
            }
        }
    }

    private fun readQueue(c: MediaController) {
        val ids = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }
        val resolved = ArrayList<Track>(ids.size)
        val missing = ArrayList<String>()
        for (id in ids) {
            val item = registry.get(id)
            if (item != null) resolved.add(item) else missing.add(id)
        }
        _queue.value = resolved
        _queueIndex.value = c.currentMediaItemIndex
        _shuffleEnabled.value = c.shuffleModeEnabled
        _repeatMode.value = c.repeatMode
        readUpcoming(c)
        // Fetch any items the UI doesn't know yet (remote cast items).
        _queueReady.value = missing.isEmpty()
        if (missing.isNotEmpty()) {
            scope.launch {
                try {
                    queueItemLoader.loadAll(missing) { id ->
                        SourceQueries.item(id) as? Track ?: error("Track not found: $id")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    _queueReady.value = true
                    return@launch
                }
                readQueue(c)
            }
        }
    }

    private fun readUpcoming(c: MediaController) {
        val timeline = c.currentTimeline
        // Next skips to another item even when the current item repeats.
        val repeat = if (c.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_OFF else c.repeatMode
        _upcoming.value = upcomingIndices(timeline.windowCount, c.currentMediaItemIndex) { index ->
            timeline.getNextWindowIndex(index, repeat, c.shuffleModeEnabled)
        }.mapNotNull { index ->
            registry.get(c.getMediaItemAt(index).mediaId)?.let { IndexedValue(index, it) }
        }
        _playOrder.value = queueIndices(
            timeline.windowCount,
            c.currentMediaItemIndex,
            previous = { timeline.getPreviousWindowIndex(it, Player.REPEAT_MODE_OFF, c.shuffleModeEnabled) },
            next = { timeline.getNextWindowIndex(it, repeat, c.shuffleModeEnabled) }
        ).mapNotNull { index ->
            registry.get(c.getMediaItemAt(index).mediaId)?.let { IndexedValue(index, it) }
        }
    }

    // ---- Commands ----

    /**
     * Start playback of [items] at [startIndex].
     *
     * Resume-where-you-left-off is decided by [ResumePolicy]: a DELIBERATE
     * single-track tap starts at the user's saved position when that is past the
     * first 15s and not within the last 10s. Play-all / shuffle / Play Next /
     * Add to queue never resume (they pass resume = false).
     */
    fun playQueue(
        items: List<Track>,
        startIndex: Int,
        playWhenReady: Boolean = true,
        resume: Boolean? = null
    ) {
        val c = controller() ?: return
        val serverUrl = sessionProvider()?.serverUrl ?: return

        // Pressing the track that is ALREADY playing from this same list must not
        // restart it from zero — treat it as "show me what's playing". Requires
        // the same queue (same context), so re-tapping a track in a different
        // album still starts playback, and a finished/idle track still replays.
        val tapped = items.getOrNull(startIndex)
        if (tapped != null &&
            c.playbackState != Player.STATE_IDLE &&
            c.playbackState != Player.STATE_ENDED &&
            c.currentMediaItem?.mediaId == tapped.id &&
            registry.sameQueue(c, items)
        ) {
            AuraLog.d(AuraTag.QUEUE, "playQueue: already playing ${tapped.id} -> now playing")
            com.hans.aura.ui.common.NavRequests.requestNowPlaying()
            return
        }

        // A new queue means a new autoplay chain and no smart order to undo.
        autoplay.reset()
        _smartShuffle.value = false
        registry.normalOrderIds = null

        val resumeAllowed = resumePolicy.allowsResume(startIndex, items.size, playWhenReady, resume)
        val resumeMs = if (resumeAllowed) resumePolicy.positionMs(tapped) else null
        if (tapped != null && resumeAllowed) {
            if (resumeMs != null) {
                AuraLog.d(
                    AuraTag.RESUME,
                    "item=${tapped.id} at=$resumeMs dur=${tapped.durationMs}"
                )
            } else {
                // No usable position on the item we were handed: either the
                // browse query didn't carry server playback state, or LibraryCache served a
                // copy fetched BEFORE this track was last played (a cached
                // position of 0 is stale, not "never played"). One item fetch
                // settles it, and it only happens on an explicit tap.
                resumeAfterFetch(c, tapped)
            }
        }

        registry.putAll(items)
        registry.setQueue(items.map { it.id })
        c.setMediaItems(
            items.map { toMediaItem(it, serverUrl) },
            startIndex,
            resumeMs ?: 0L
        )
        c.playWhenReady = playWhenReady
        c.prepare()
    }

    /** Late resume for items whose server playback state the browse query didn't return. */
    private fun resumeAfterFetch(c: MediaController, item: Track) {
        scope.launch {
            val full = runCatching {
                SourceQueries.item(item.id) as? Track
            }.getOrNull() ?: return@launch
            registry.put(full)
            AuraLog.d(
                AuraTag.RESUME,
                "fetched item=${item.id} resume=${full.resumePositionMs ?: -1} " +
                    "dur=${full.durationMs}"
            )
            val ms = resumePolicy.positionMs(full) ?: return@launch
            onMainThread {
                // Only when the track actually started from the top; a user who
                // already scrubbed or skipped must not be moved back.
                if (c.currentMediaItem?.mediaId == item.id && c.currentPosition < 5_000) {
                    c.seekTo(ms)
                    playback.publishPosition(ms)
                    AuraLog.d(
                        AuraTag.RESUME,
                        "item=${item.id} at=$ms dur=${full.durationMs} (late)"
                    )
                }
            }
        }
    }

    /** Insert after the current track ("Play Next"); starts playback if idle. */
    fun playNext(items: List<Track>) {
        val c = controller() ?: return
        val serverUrl = sessionProvider()?.serverUrl ?: return
        registry.putAll(items)
        val wasEmpty = c.mediaItemCount == 0
        val at = if (wasEmpty) 0 else c.currentMediaItemIndex + 1
        AuraLog.d(
            AuraTag.QUEUE,
            "playNext ${items.map { it.name }} at=$at size=${c.mediaItemCount}"
        )
        c.addMediaItems(at, items.map { toMediaItem(it, serverUrl) })
        registry.insertIntoQueue(at, items.map { it.id })
        if (wasEmpty) { c.prepare(); c.playWhenReady = true }
    }

    /** Append at the end of the queue; starts playback if idle. */
    fun addToQueue(items: List<Track>) {
        val c = controller() ?: return
        val serverUrl = sessionProvider()?.serverUrl ?: return
        registry.putAll(items)
        val wasEmpty = c.mediaItemCount == 0
        AuraLog.d(
            AuraTag.QUEUE,
            "addToQueue ${items.map { it.name }} size=${c.mediaItemCount}"
        )
        c.addMediaItems(items.map { toMediaItem(it, serverUrl) })
        registry.appendToQueue(items.map { it.id })
        if (wasEmpty) { c.prepare(); c.playWhenReady = true }
    }

    fun playPause() { controller()?.let { if (it.isPlaying) it.pause() else it.play() } }
    fun next() { controller()?.seekToNextMediaItem() }
    fun previous() { controller()?.seekToPreviousMediaItem() }
    fun seekTo(ms: Long) { controller()?.seekTo(ms) }
    fun seekBy(deltaMs: Long) = onMainThread {
        val c = controller() ?: return@onMainThread
        val duration = c.duration
        if (duration <= 0) return@onMainThread
        val position = currentPositionMs().coerceIn(0, duration)
        c.seekTo(position + deltaMs.coerceIn(-position, duration - position))
    }
    fun skipToQueue(index: Int) { controller()?.seekTo(index, 0) }

    fun removeFromQueue(index: Int) = onMainThread {
        val c = controller() ?: return@onMainThread
        if (index !in 0 until c.mediaItemCount) return@onMainThread
        c.removeMediaItem(index)
        registry.removeFromQueue(index)
        AuraLog.d(AuraTag.QUEUE, "remove index $index, ${c.mediaItemCount} left")
    }

    /**
     * Moves the queue entry at [from] to position [to] (menu-driven reorder —
     * there is no pointer on a TV).
     *
     * MediaController calls throw "Player is accessed on the wrong thread" from
     * anything but main, so the command is marshalled onto the main thread when
     * a caller is not already there. The queue mirror itself is refreshed by the
     * Player.Listener's onTimelineChanged, which Media3 fires for this mutation
     * (the ordering of registry reads is id-derived, so no registry edit is
     * needed — only the id snapshot, which is what the re-tap check reads).
     */
    fun moveInQueue(from: Int, to: Int) = onMainThread {
        val c = controller() ?: return@onMainThread
        val count = c.mediaItemCount
        if (from !in 0 until count || to !in 0 until count || from == to) return@onMainThread
        c.moveMediaItem(from, to)
        registry.moveInQueue(from, to)
        AuraLog.d(AuraTag.QUEUE, "move from=$from to=$to of $count")
    }

    /**
     * Drops every entry AFTER the current one and keeps playing what is
     * playing. Deliberately not a full queue clear / stop: the current track
     * must not even hiccup, and the user's "up next" is the only thing going.
     */
    fun clearUpcoming() = onMainThread {
        val c = controller() ?: return@onMainThread
        val current = c.currentMediaItemIndex
        val count = c.mediaItemCount
        if (current < 0 || current + 1 >= count) {
            AuraLog.d(AuraTag.QUEUE, "clearUpcoming: nothing after index $current")
            return@onMainThread
        }
        c.removeMediaItems(current + 1, count)
        registry.keepUpTo(current + 1)
        AuraLog.d(
            AuraTag.QUEUE,
            "clearUpcoming: removed ${count - (current + 1)}, kept ${current + 1}"
        )
    }

    fun setShuffle(enabled: Boolean) { controller()?.shuffleModeEnabled = enabled }
    fun setRepeat(mode: Int) { controller()?.repeatMode = mode }

    // ---- Sleep timer -------------------------------------------------------

    /** Pause playback after [seconds] (UI passes minutes * 60; tests pass 5). */
    fun startSleepTimer(seconds: Int) = sleep.start(seconds)

    fun cancelSleepTimer() = sleep.cancel()

    // ---- Smart shuffle -----------------------------------------------------

    /**
     * Reorders the UPCOMING items least-played / longest-unplayed first, keeping
     * ~20% of the slots for well-loved tracks ([QueueRegistry.smartOrder]). The
     * current track keeps playing and its position is untouched: only the
     * entries after it are removed and re-added in the new order.
     */
    fun applySmartOrder() = onMainThread {
        val c = controller() ?: return@onMainThread
        val serverUrl = sessionProvider()?.serverUrl ?: return@onMainThread
        val cur = c.currentMediaItemIndex
        val count = c.mediaItemCount
        if (cur < 0 || cur + 1 >= count) {
            AuraLog.d(AuraTag.SMART, "reordered=0 kept=none (nothing upcoming)")
            return@onMainThread
        }
        val upcoming = (cur + 1 until count).mapNotNull { i -> registry.get(c.getMediaItemAt(i).mediaId) }
        if (upcoming.size < 2) {
            AuraLog.d(AuraTag.SMART, "reordered=0 kept=${c.currentMediaItem?.mediaId}")
            return@onMainThread
        }
        if (registry.normalOrderIds == null) registry.normalOrderIds = upcoming.map { it.id }
        val reordered = QueueRegistry.smartOrder(upcoming)
        val prefix = (0..cur).map { c.getMediaItemAt(it).mediaId }
        c.removeMediaItems(cur + 1, count)
        c.addMediaItems(reordered.map { toMediaItem(it, serverUrl) })
        registry.setQueue(prefix + reordered.map { it.id })
        _smartShuffle.value = true
        AuraLog.d(
            AuraTag.SMART,
            "reordered=${reordered.size} kept=${c.currentMediaItem?.mediaId}"
        )
    }

    /** Puts the upcoming items back in the order they had before [applySmartOrder]. */
    fun restoreNormalOrder() = onMainThread {
        val c = controller() ?: return@onMainThread
        val serverUrl = sessionProvider()?.serverUrl ?: return@onMainThread
        val saved = registry.normalOrderIds ?: return@onMainThread
        val cur = c.currentMediaItemIndex
        if (cur < 0 || cur + 1 >= c.mediaItemCount) {
            _smartShuffle.value = false
            registry.normalOrderIds = null
            return@onMainThread
        }
        val live = (cur + 1 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }
        // Keep the saved order, but only for entries still queued; anything
        // appended since (autoplay) keeps its place at the end.
        val target = restoredUpcomingOrder(saved, live)
        val items = target.mapNotNull { registry.get(it) }
        val count = c.mediaItemCount
        c.removeMediaItems(cur + 1, count)
        c.addMediaItems(items.map { toMediaItem(it, serverUrl) })
        registry.setQueue((0..cur).map { c.getMediaItemAt(it).mediaId } + target)
        registry.normalOrderIds = null
        _smartShuffle.value = false
        AuraLog.d(AuraTag.SMART, "restored=${items.size} kept=${c.currentMediaItem?.mediaId}")
    }

    /** Media3 player access is main-thread-only; run now when already there. */
    private fun onMainThread(block: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            block()
        } else {
            android.os.Handler(android.os.Looper.getMainLooper()).post(block)
        }
    }

    /**
     * Releases this connection's MediaController and cancels its scope WITHOUT
     * stopping playback or the playback service.
     *
     * This is the Activity-UI disposal path: the service keeps playing (it is
     * the owner of the player, this object is only a view), so a config change or
     * a UI teardown does not cut the music. Use [teardown] on sign-out, where the
     * player's token/host belong to the dead server and must go too.
     */
    fun disconnect() {
        if (closed) return
        closed = true
        // Stop listening before releasing so no callback lands mid-cancel.
        playback.setListener(null)
        playback.release()
        _currentItem.value = null
        _isPlaying.value = false
        _queue.value = emptyList()
        _queueIndex.value = 0
        sleep.clear()
        scope.cancel()
    }

    /**
     * Drop everything this connection holds for the CURRENT server, on the way
     * to a login screen. Main thread only (MediaController/ExoPlayer rule).
     *
     * Without this the old server keeps streaming while the UI shows the new
     * one (the service owns the player, the controller is only a view) and a
     * stale mediaId registry would resolve new-server item ids to OLD-server
     * metadata — wrong artwork and wrong tracklists.
     *
     * Order matters: stop + clear items, release the controller (that is the
     * disconnect), THEN stop the service so its ExoPlayer and HTTP data source
     * — which carry the old token/host — go away too.
     *
     * @return number of registry entries dropped (for the AURA_AUTH breadcrumb).
     */
    fun teardown(): Int {
        if (closed) return 0
        closed = true
        val items = registry.clear()
        playback.setListener(null)
        playback.controller.value?.let { c ->
            runCatching { c.stop() }
            runCatching { c.clearMediaItems() }
        }
        playback.release()
        _currentItem.value = null
        _isPlaying.value = false
        _queue.value = emptyList()
        _queueIndex.value = 0
        _shuffleEnabled.value = false
        _repeatMode.value = Player.REPEAT_MODE_OFF
        _smartShuffle.value = false
        sleep.clear()
        registry.resetOrderState()
        autoplay.reset()
        // The ticker and any in-flight metadata fetch belong to the dead session.
        scope.cancel()
        runCatching {
            appContext.stopService(
                android.content.Intent(appContext, PlaybackService::class.java)
            )
        }
        return items
    }

    companion object {
        /**
         * Smart shuffle (pure, deterministic). Delegates to [QueueRegistry] so
         * the ordering rule has exactly one implementation.
         */
        fun smartOrder(items: List<Track>): List<Track> =
            QueueRegistry.smartOrder(items)


        /** Atmos = DD+ with JOC object coding; only E-AC3 JOC survives as Atmos. */
        fun isAtmos(item: Track?): Boolean = item?.isAtmos == true

        fun toMediaItem(item: Track, serverUrl: String): MediaItem =
            com.hans.aura.player.controller.toMediaItem(item, serverUrl)
    }
}
