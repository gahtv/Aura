package com.hans.aura.ui.player

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.hans.aura.data.AuthStore
import com.hans.aura.data.JellyfinItem
import com.hans.aura.data.Prefs
import com.hans.aura.playback.PlaybackService
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Single shared connection to the playback session, exposed as state flows.
 *
 * MediaItems carry only mediaId + URI (media3 1.6 exposes no extras payload);
 * the full JellyfinItem is kept in an in-process registry keyed by mediaId.
 * The stream URI never embeds the token — the playback service's DataSource
 * factory adds the Authorization header to every request.
 */
class PlayerConnection(
    context: Context,
    private val jellyfin: com.hans.aura.data.JellyfinClient,
    sessionProvider: () -> com.hans.aura.data.Session?
) {

    private val appContext = context.applicationContext
    private val sessionProvider: () -> com.hans.aura.data.Session? = sessionProvider
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** mediaId -> full item (for UI metadata, Atmos badge, lyrics, reporting). */
    private val registry = HashMap<String, JellyfinItem>()

    /**
     * The queue's mediaIds in playback order, as Aura built it.
     *
     * The "re-tapping the already-playing row opens Now Playing instead of
     * restarting it" check reads this. A reorder changes the player's order but
     * NOT the order of the list the user tapped (a playlist/album still holds
     * its server order), so comparing the tapped list against the live player
     * order turned a reordered queue into "restart the track". Every queue
     * mutation below mutates this snapshot too; [sameQueue] additionally
     * verifies it against the real player order so a mutation Aura did not make
     * (remote Play Next/Last) falls back to the strict comparison.
     */
    private var queueSnapshot: List<String>? = null

    /** Set by [teardown]: the session is being replaced, never re-attach. */
    @Volatile
    private var tornDown = false

    /** Seed id of the last autoplay continuation, so one track end appends once. */
    private var lastAutoplaySeed: String? = null
    private var autoplayInFlight = false

    /** Upcoming order before [applySmartOrder], so "Normal order" can restore it. */
    private var normalOrderIds: List<String>? = null

    private var sleepJob: kotlinx.coroutines.Job? = null

    private val _controller = MutableStateFlow<MediaController?>(null)
    private val _currentItem = MutableStateFlow<JellyfinItem?>(null)
    private val _isPlaying = MutableStateFlow(false)
    private val _positionMs = MutableStateFlow(0L)
    private val _durationMs = MutableStateFlow(0L)
    private val _queue = MutableStateFlow<List<JellyfinItem>>(emptyList())
    private val _queueIndex = MutableStateFlow(0)
    private val _shuffleEnabled = MutableStateFlow(false)
    private val _repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF)
    private val _sleepRemaining = MutableStateFlow<Long?>(null)
    private val _smartShuffle = MutableStateFlow(false)

    /** Milliseconds left on the sleep timer, null when no timer is running. */
    val sleepRemaining: StateFlow<Long?> = _sleepRemaining
    /** True while the queue's upcoming items are in smart (least-played) order. */
    val smartShuffle: StateFlow<Boolean> = _smartShuffle

    val current: StateFlow<JellyfinItem?> = _currentItem
    val isPlaying: StateFlow<Boolean> = _isPlaying
    val positionMs: StateFlow<Long> = _positionMs
    val durationMs: StateFlow<Long> = _durationMs
    val queue: StateFlow<List<JellyfinItem>> = _queue
    val queueIndex: StateFlow<Int> = _queueIndex
    val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled
    val repeatMode: StateFlow<Int> = _repeatMode

    init {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            val c = future.get()
            // A teardown may land while the controller is still connecting;
            // never re-attach a controller after the session was dropped.
            if (tornDown) {
                runCatching { c.release() }
                return@addListener
            }
            _controller.value = c
            attach(c)
        }, ContextCompat.getMainExecutor(context))

        // Position ticker: drives progress bars and lyric sync.
        scope.launch {
            while (true) {
                _controller.value?.let { c ->
                    _positionMs.value = c.currentPosition.coerceAtLeast(0)
                    _durationMs.value = c.duration.coerceAtLeast(0)
                }
                delay(500)
            }
        }
    }

    private fun attach(c: MediaController) {
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                syncItem(c, mediaItem?.mediaId)
                _queueIndex.value = c.currentMediaItemIndex
                // The ticker only runs every 500ms; without this, positionMs
                // (and durationMs) still hold the previous track's values for
                // up to half a second after the item changes, which consumers
                // like the lyrics pane read as a stale time into the new song.
                _positionMs.value = 0
                _durationMs.value = 0
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
            }

            override fun onShuffleModeEnabledChanged(enabled: Boolean) {
                _shuffleEnabled.value = enabled
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                _repeatMode.value = repeatMode
            }

            override fun onPlaybackStateChanged(state: Int) {
                readQueue(c)
                // Queue ran out at the last track: keep the music going.
                if (state == Player.STATE_ENDED) maybeAutoplay(c)
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                // Remote Play Next/Last mutate the playlist without a playback
                // state change — keep the queue screen in sync.
                readQueue(c)
            }
        }
        c.addListener(listener)
        syncItem(c, c.currentMediaItem?.mediaId)
        _isPlaying.value = c.isPlaying
        readQueue(c)
    }

    /** Resolve a mediaId to a JellyfinItem, fetching from the server when the
     *  item wasn't queued by this UI (e.g. remote "Play On" casts). */
    private fun syncItem(c: MediaController, mediaId: String?) {
        if (mediaId == null) {
            _currentItem.value = null
            return
        }
        val known = synchronized(registry) { registry[mediaId] }
        if (known != null) {
            _currentItem.value = known
        } else {
            _currentItem.value = null
            scope.launch {
                runCatching {
                    val session = sessionProvider() ?: return@launch
                    jellyfin.api.getItem(session.userId, mediaId)
                }.onSuccess { item ->
                    synchronized(registry) { registry[mediaId] = item }
                    // Only publish if this is still the current item.
                    if (c.currentMediaItem?.mediaId == mediaId) {
                        _currentItem.value = item
                    }
                    readQueue(c)
                }
            }
        }
    }

    private fun readQueue(c: MediaController) {
        val ids = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }
        val resolved = ArrayList<JellyfinItem>(ids.size)
        val missing = ArrayList<String>()
        synchronized(registry) {
            for (id in ids) {
                val item = registry[id]
                if (item != null) resolved.add(item) else missing.add(id)
            }
        }
        _queue.value = resolved
        _queueIndex.value = c.currentMediaItemIndex
        // Fetch any items the UI doesn't know yet (remote cast items).
        if (missing.isNotEmpty()) {
            scope.launch {
                runCatching {
                    val session = sessionProvider() ?: return@launch
                    for (id in missing) {
                        val item = jellyfin.api.getItem(session.userId, id)
                        synchronized(registry) { registry[id] = item }
                    }
                    readQueue(c)
                }
            }
        }
    }

    // ---- Commands ----

    /**
     * Start playback of [items] at [startIndex].
     *
     * Resume-where-you-left-off: a DELIBERATE single-track tap starts at the
     * user's saved position (UserData.PlaybackPositionTicks) when that position
     * is past the first 15s and not within the last 10s. Play-all / shuffle /
     * Play Next / Add to queue must never resume, which is what [resume] is for:
     * null means "infer" — a list started at its first entry is a
     * play-all-from-here and starts at 0, anything else is a tap on a row.
     */
    fun playQueue(
        items: List<JellyfinItem>,
        startIndex: Int,
        playWhenReady: Boolean = true,
        resume: Boolean? = null
    ) {
        val c = _controller.value ?: return
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
            sameQueue(c, items)
        ) {
            android.util.Log.d("AURA_QUEUE", "playQueue: already playing ${tapped.id} -> now playing")
            com.hans.aura.ui.common.NavRequests.requestNowPlaying()
            return
        }

        // A new queue means a new autoplay chain and no smart order to undo.
        lastAutoplaySeed = null
        _smartShuffle.value = false
        normalOrderIds = null

        val resumeAllowed = playWhenReady && (resume ?: (startIndex > 0 || items.size == 1))
        val resumeMs = if (resumeAllowed) resumePositionMs(tapped) else null
        if (tapped != null && resumeAllowed) {
            if (resumeMs != null) {
                android.util.Log.d(
                    "AURA_RESUME",
                    "item=${tapped.id} at=$resumeMs dur=${tapped.durationMs}"
                )
            } else {
                // No usable position on the item we were handed: either the
                // browse query didn't carry UserData, or LibraryCache served a
                // copy fetched BEFORE this track was last played (a cached
                // position of 0 is stale, not "never played"). One item fetch
                // settles it, and it only happens on an explicit tap.
                resumeAfterFetch(c, tapped)
            }
        }

        synchronized(registry) { items.forEach { registry[it.id] = it } }
        queueSnapshot = items.map { it.id }
        c.setMediaItems(
            items.map { toMediaItem(it, serverUrl) },
            startIndex,
            resumeMs ?: 0L
        )
        c.playWhenReady = playWhenReady
        c.prepare()
    }

    /**
     * Resume position for [item], or null when starting at zero is right.
     * Shorter than 15s in is "just started"; inside the last 10s is "finished".
     */
    private fun resumePositionMs(item: JellyfinItem?): Long? {
        if (item == null) return null
        // A locally remembered position wins: Jellyfin 12.2.0 never writes
        // UserData.PlaybackPositionTicks from playback reports (verified against
        // the live server), so the server value is only a fallback for clients
        // that did manage to set it.
        val local = com.hans.aura.data.ResumePoints.position(item.id)
        val ticks = item.userData?.playbackPositionTicks
        val pos = local ?: (ticks?.let { it / 10_000L } ?: return null)
        if (pos <= RESUME_MIN_MS) return null
        val dur = item.durationMs
        if (dur > 0 && pos >= dur - RESUME_TAIL_MS) return null
        return pos
    }

    /** Late resume for items whose UserData the browse query didn't return. */
    private fun resumeAfterFetch(c: MediaController, item: JellyfinItem) {
        scope.launch {
            val full = runCatching {
                val session = sessionProvider() ?: return@launch
                jellyfin.api.getItem(session.userId, item.id)
            }.getOrNull() ?: return@launch
            synchronized(registry) { registry[item.id] = full }
            android.util.Log.d(
                "AURA_RESUME",
                "fetched item=${item.id} ticks=${full.userData?.playbackPositionTicks ?: -1} " +
                    "dur=${full.durationMs}"
            )
            val ms = resumePositionMs(full) ?: return@launch
            onMainThread {
                // Only when the track actually started from the top; a user who
                // already scrubbed or skipped must not be moved back.
                if (c.currentMediaItem?.mediaId == item.id && c.currentPosition < 5_000) {
                    c.seekTo(ms)
                    _positionMs.value = ms
                    android.util.Log.d(
                        "AURA_RESUME",
                        "item=${item.id} at=$ms dur=${full.durationMs} (late)"
                    )
                }
            }
        }
    }

    /**
     * True when the tapped list is the queue that is playing.
     *
     * Content is compared as a multiset, not in order: after a Move the queue's
     * order legitimately differs from the tapped list's order, and an
     * order-sensitive check would restart the current track instead of opening
     * Now Playing. Duplicate ids (a playlist can hold the same track twice) are
     * preserved by sorting the two lists rather than deduping into a Set.
     */
    private fun sameQueue(c: androidx.media3.common.Player, items: List<JellyfinItem>): Boolean {
        val live = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }
        if (live.sorted() != items.map { it.id }.sorted()) return false
        // If the queue was mutated behind Aura's back (remote Play Next/Last),
        // the snapshot is stale — fall back to requiring the exact same order.
        val snapshot = queueSnapshot
        return snapshot == null || snapshot == live
    }

    /** Insert after the current track ("Play Next"); starts playback if idle. */
    fun playNext(items: List<JellyfinItem>) {
        val c = _controller.value ?: return
        val serverUrl = sessionProvider()?.serverUrl ?: return
        synchronized(registry) { items.forEach { registry[it.id] = it } }
        val wasEmpty = c.mediaItemCount == 0
        val at = if (wasEmpty) 0 else c.currentMediaItemIndex + 1
        android.util.Log.d(
            "AURA_QUEUE",
            "playNext ${items.map { it.name }} at=$at size=${c.mediaItemCount}"
        )
        c.addMediaItems(at, items.map { toMediaItem(it, serverUrl) })
        queueSnapshot = queueSnapshot?.toMutableList()?.also { snap ->
            // A remote Play Next can arrive between our captures; clamp so the
            // snapshot never indexes past its own end.
            snap.addAll(at.coerceIn(0, snap.size), items.map { it.id })
        }
        if (wasEmpty) { c.prepare(); c.playWhenReady = true }
    }

    /** Append at the end of the queue; starts playback if idle. */
    fun addToQueue(items: List<JellyfinItem>) {
        val c = _controller.value ?: return
        val serverUrl = sessionProvider()?.serverUrl ?: return
        synchronized(registry) { items.forEach { registry[it.id] = it } }
        val wasEmpty = c.mediaItemCount == 0
        android.util.Log.d(
            "AURA_QUEUE",
            "addToQueue ${items.map { it.name }} size=${c.mediaItemCount}"
        )
        c.addMediaItems(items.map { toMediaItem(it, serverUrl) })
        queueSnapshot = queueSnapshot?.toMutableList()?.also { snap ->
            snap.addAll(items.map { it.id })
        }
        if (wasEmpty) { c.prepare(); c.playWhenReady = true }
    }

    fun playPause() { _controller.value?.let { if (it.isPlaying) it.pause() else it.play() } }
    fun next() { _controller.value?.seekToNextMediaItem() }
    fun previous() { _controller.value?.seekToPreviousMediaItem() }
    fun seekTo(ms: Long) { _controller.value?.seekTo(ms) }
    fun skipToQueue(index: Int) { _controller.value?.seekTo(index, 0) }

    fun removeFromQueue(index: Int) = onMainThread {
        val c = _controller.value ?: return@onMainThread
        if (index !in 0 until c.mediaItemCount) return@onMainThread
        c.removeMediaItem(index)
        queueSnapshot = queueSnapshot?.toMutableList()?.also { snap ->
            if (index in snap.indices) snap.removeAt(index)
        }
        android.util.Log.d("AURA_QUEUE", "remove index $index, ${c.mediaItemCount} left")
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
        val c = _controller.value ?: return@onMainThread
        val count = c.mediaItemCount
        if (from !in 0 until count || to !in 0 until count || from == to) return@onMainThread
        c.moveMediaItem(from, to)
        queueSnapshot = queueSnapshot?.toMutableList()?.also { snap ->
            if (from in snap.indices) snap.add(to, snap.removeAt(from))
        }
        android.util.Log.d("AURA_QUEUE", "move from=$from to=$to of $count")
    }

    /**
     * Drops every entry AFTER the current one and keeps playing what is
     * playing. Deliberately not a full queue clear / stop: the current track
     * must not even hiccup, and the user's "up next" is the only thing going.
     */
    fun clearUpcoming() = onMainThread {
        val c = _controller.value ?: return@onMainThread
        val current = c.currentMediaItemIndex
        val count = c.mediaItemCount
        if (current < 0 || current + 1 >= count) {
            android.util.Log.d("AURA_QUEUE", "clearUpcoming: nothing after index $current")
            return@onMainThread
        }
        c.removeMediaItems(current + 1, count)
        queueSnapshot = queueSnapshot?.take(current + 1)
        android.util.Log.d("AURA_QUEUE", "clearUpcoming: removed ${count - (current + 1)}, kept ${current + 1}")
    }

    fun setShuffle(enabled: Boolean) { _controller.value?.shuffleModeEnabled = enabled }
    fun setRepeat(mode: Int) { _controller.value?.repeatMode = mode }

    // ---- Keep the music going (autoplay when the queue runs out) ----------

    /**
     * The queue reached its last item and would END. Append an Instant Mix
     * seeded from that track (minus anything already queued, max 20) so the
     * music keeps playing.
     *
     * Never fires when the user pressed stop (that lands in STATE_IDLE, not
     * ENDED), when repeat is on (a repeat handles the end itself), when the
     * preference is off, or twice for the same seed.
     */
    private fun maybeAutoplay(c: MediaController) {
        fun skip(why: String) {
            android.util.Log.d("AURA_AUTOPLAY", "skip: $why")
        }
        if (!Prefs.autoplayMusic) return skip("pref off")
        if (c.repeatMode != Player.REPEAT_MODE_OFF) return skip("repeat on")
        if (c.mediaItemCount == 0) return skip("empty queue")
        // Deliberately NOT gated on playWhenReady: a player that has just
        // reached the end of its last item can report playWhenReady=false even
        // though the user never asked to stop, and gating on it meant the queue
        // simply died (seen on the emulator). Ending at the last item IS the
        // signal; a user-initiated stop does not produce STATE_ENDED.
        if (autoplayInFlight) return skip("in flight")
        val seed = c.getMediaItemAt(c.mediaItemCount - 1).mediaId
        if (seed == lastAutoplaySeed) return skip("same seed $seed")
        val session = sessionProvider() ?: return
        val serverUrl = session.serverUrl
        // Read the player on the main thread; the coroutine below must not.
        val known = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }.toSet()

        lastAutoplaySeed = seed
        autoplayInFlight = true
        scope.launch {
            val added = runCatching {
                val resp = jellyfin.api.getInstantMix(seed, session.userId, limit = 40)
                val fresh = resp.items
                    .filter { it.id !in known && it.type != "MusicAlbum" }
                    .distinctBy { it.id }
                    .take(20)
                fresh
            }.onFailure {
                android.util.Log.w("AURA_AUTOPLAY", "instant mix failed: ${it.message}")
            }.getOrDefault(emptyList())

            onMainThread {
                autoplayInFlight = false
                if (added.isEmpty()) {
                    // Allow a later end on the same track to try again.
                    if (lastAutoplaySeed == seed) lastAutoplaySeed = null
                    android.util.Log.d("AURA_AUTOPLAY", "appended=0 seed=$seed")
                    return@onMainThread
                }
                synchronized(registry) { added.forEach { registry[it.id] = it } }
                val atEnd = c.mediaItemCount
                c.addMediaItems(added.map { toMediaItem(it, serverUrl) })
                queueSnapshot = queueSnapshot?.toMutableList()?.also { snap ->
                    snap.addAll(added.map { it.id })
                }
                // ENDED + appended items: step onto the first new track and
                // re-prepare, otherwise the player sits on the finished last one.
                c.seekToDefaultPosition(atEnd)
                c.prepare()
                c.playWhenReady = true
                android.util.Log.d("AURA_AUTOPLAY", "appended=${added.size} seed=$seed")
            }
        }
    }

    // ---- Sleep timer -------------------------------------------------------

    /**
     * Pause playback after [seconds] (UI passes minutes * 60; tests pass 5).
     * On expiry the player is PAUSED — never stopped, never unqueued — so the
     * user can pick up where the timer cut in.
     */
    fun startSleepTimer(seconds: Int) {
        if (seconds <= 0) {
            cancelSleepTimer()
            return
        }
        sleepJob?.cancel()
        android.util.Log.d("AURA_SLEEP", "start=$seconds")
        _sleepRemaining.value = seconds * 1000L
        sleepJob = scope.launch {
            var left = seconds * 1000L
            while (left > 0) {
                delay(SLEEP_TICK_MS)
                left = (left - SLEEP_TICK_MS).coerceAtLeast(0)
                _sleepRemaining.value = left
            }
            _sleepRemaining.value = null
            sleepJob = null
            onMainThread { _controller.value?.pause() }
            android.util.Log.d("AURA_SLEEP", "fired")
        }
    }

    fun cancelSleepTimer() {
        sleepJob?.cancel()
        sleepJob = null
        val wasRunning = _sleepRemaining.value != null
        _sleepRemaining.value = null
        if (wasRunning) android.util.Log.d("AURA_SLEEP", "cancel")
    }

    // ---- Smart shuffle -----------------------------------------------------

    /**
     * Reorders the UPCOMING items least-played / longest-unplayed first, keeping
     * ~20% of the slots for well-loved tracks ([smartOrder]). The current track
     * keeps playing and its position is untouched: only the entries after it are
     * removed and re-added in the new order.
     */
    fun applySmartOrder() = onMainThread {
        val c = _controller.value ?: return@onMainThread
        val serverUrl = sessionProvider()?.serverUrl ?: return@onMainThread
        val cur = c.currentMediaItemIndex
        val count = c.mediaItemCount
        if (cur < 0 || cur + 1 >= count) {
            android.util.Log.d("AURA_SMART", "reordered=0 kept=none (nothing upcoming)")
            return@onMainThread
        }
        val upcoming = (cur + 1 until count).mapNotNull { i ->
            val id = c.getMediaItemAt(i).mediaId
            synchronized(registry) { registry[id] }
        }
        if (upcoming.size < 2) {
            android.util.Log.d("AURA_SMART", "reordered=0 kept=${c.currentMediaItem?.mediaId}")
            return@onMainThread
        }
        if (normalOrderIds == null) normalOrderIds = upcoming.map { it.id }
        val reordered = smartOrder(upcoming)
        val prefix = (0..cur).map { c.getMediaItemAt(it).mediaId }
        c.removeMediaItems(cur + 1, count)
        c.addMediaItems(reordered.map { toMediaItem(it, serverUrl) })
        queueSnapshot = prefix + reordered.map { it.id }
        _smartShuffle.value = true
        android.util.Log.d(
            "AURA_SMART",
            "reordered=${reordered.size} kept=${c.currentMediaItem?.mediaId}"
        )
    }

    /** Puts the upcoming items back in the order they had before [applySmartOrder]. */
    fun restoreNormalOrder() = onMainThread {
        val c = _controller.value ?: return@onMainThread
        val serverUrl = sessionProvider()?.serverUrl ?: return@onMainThread
        val saved = normalOrderIds ?: return@onMainThread
        val cur = c.currentMediaItemIndex
        if (cur < 0 || cur + 1 >= c.mediaItemCount) {
            _smartShuffle.value = false
            normalOrderIds = null
            return@onMainThread
        }
        val live = (cur + 1 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }
        // Keep the saved order, but only for entries still queued; anything
        // appended since (autoplay) keeps its place at the end.
        val target = saved.filter { it in live } + live.filter { it !in saved }
        val items = target.mapNotNull { id -> synchronized(registry) { registry[id] } }
        val count = c.mediaItemCount
        c.removeMediaItems(cur + 1, count)
        c.addMediaItems(items.map { toMediaItem(it, serverUrl) })
        queueSnapshot = (0..cur).map { c.getMediaItemAt(it).mediaId } + target
        normalOrderIds = null
        _smartShuffle.value = false
        android.util.Log.d("AURA_SMART", "restored=${items.size} kept=${c.currentMediaItem?.mediaId}")
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
        tornDown = true
        val items = synchronized(registry) {
            val size = registry.size
            registry.clear()
            size
        }
        _controller.value?.let { c ->
            runCatching { c.stop() }
            runCatching { c.clearMediaItems() }
            runCatching { c.release() }
        }
        _controller.value = null
        _currentItem.value = null
        _isPlaying.value = false
        _positionMs.value = 0L
        _durationMs.value = 0L
        _queue.value = emptyList()
        _queueIndex.value = 0
        _shuffleEnabled.value = false
        _repeatMode.value = Player.REPEAT_MODE_OFF
        _sleepRemaining.value = null
        _smartShuffle.value = false
        sleepJob = null
        normalOrderIds = null
        lastAutoplaySeed = null
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
        private val itemJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }

        /** Resume only past the first 15s of a track… */
        private const val RESUME_MIN_MS = 15_000L
        /** …and never inside its last 10s (that would replay a finished track). */
        private const val RESUME_TAIL_MS = 10_000L
        private const val SLEEP_TICK_MS = 1_000L

        /**
         * Smart shuffle: least-played and longest-unplayed first, with ~20% of
         * the slots reserved for well-loved tracks so the mix still feels
         * familiar. Pure and deterministic (no RNG) — same input, same order —
         * so it is unit-testable and its result can be undone by restoring the
         * list the caller kept.
         */
        fun smartOrder(items: List<JellyfinItem>): List<JellyfinItem> {
            if (items.size < 3) return items
            val ranked = items.sortedWith(
                compareBy<JellyfinItem> { it.userData?.playCount ?: 0 }
                    .thenBy { lastPlayedEpochMs(it.userData?.lastPlayedDate) }
                    .thenBy { it.id }
            )
            val lovedCount = (ranked.size * 0.2f).roundToInt()
            if (lovedCount <= 0) return ranked
            val loved = ranked.takeLast(lovedCount) // most-played
            val fresh = ranked.take(ranked.size - lovedCount)
            if (fresh.isEmpty()) return ranked
            // Deterministic interleave: one loved track after every `step`
            // fresh ones, so favourites arrive steadily instead of in a block.
            val step = maxOf(1, fresh.size / loved.size)
            val out = ArrayList<JellyfinItem>(ranked.size)
            var li = 0
            fresh.forEachIndexed { i, item ->
                out.add(item)
                if ((i + 1) % step == 0 && li < loved.size) out.add(loved[li++])
            }
            while (li < loved.size) out.add(loved[li++])
            return out
        }

        /** ISO-8601 UTC stamp -> epoch millis; 0 (oldest) when absent/unparsable. */
        private fun lastPlayedEpochMs(raw: String?): Long {
            if (raw.isNullOrBlank()) return 0L
            val m = Regex("(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})").find(raw)
                ?: return 0L
            val g = m.groupValues
            return runCatching {
                val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
                cal.clear()
                cal.set(
                    g[1].toInt(), g[2].toInt() - 1, g[3].toInt(),
                    g[4].toInt(), g[5].toInt(), g[6].toInt()
                )
                cal.timeInMillis
            }.getOrDefault(0L)
        }

        fun serialize(item: JellyfinItem): String = itemJson.encodeToString(item)
        fun deserialize(raw: String): JellyfinItem? =
            runCatching { itemJson.decodeFromString<JellyfinItem>(raw) }.getOrNull()

        /** Atmos = DD+ with JOC object coding; only E-AC3 JOC survives as Atmos. */
        fun isAtmos(item: JellyfinItem?): Boolean =
            item?.mediaSources?.any { src ->
                src.mediaStreams?.any { s ->
                    s.codec.equals("eac3", true) &&
                        (s.profile?.contains("JOC", true) == true ||
                            s.profile?.contains("Dolby Atmos", true) == true)
                } == true
            } == true

        fun toMediaItem(item: JellyfinItem, serverUrl: String): MediaItem {
            val container = item.mediaSources?.firstOrNull()?.container
            return MediaItem.Builder()
                .setMediaId(item.id)
                .setUri(AuthStore.audioStreamUrl(serverUrl, item.id, container))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(item.name)
                        .setArtist(item.artistNames.ifBlank { "Unknown artist" })
                        .setAlbumTitle(item.album)
                        .build()
                )
                .build()
        }
    }
}
