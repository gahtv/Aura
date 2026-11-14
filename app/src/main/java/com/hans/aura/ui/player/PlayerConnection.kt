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
import com.hans.aura.playback.PlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    private val sessionProvider: () -> com.hans.aura.data.Session? = sessionProvider
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** mediaId -> full item (for UI metadata, Atmos badge, lyrics, reporting). */
    private val registry = HashMap<String, JellyfinItem>()

    private val _controller = MutableStateFlow<MediaController?>(null)
    private val _currentItem = MutableStateFlow<JellyfinItem?>(null)
    private val _isPlaying = MutableStateFlow(false)
    private val _positionMs = MutableStateFlow(0L)
    private val _durationMs = MutableStateFlow(0L)
    private val _queue = MutableStateFlow<List<JellyfinItem>>(emptyList())
    private val _queueIndex = MutableStateFlow(0)
    private val _shuffleEnabled = MutableStateFlow(false)
    private val _repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF)

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

    fun playQueue(items: List<JellyfinItem>, startIndex: Int, playWhenReady: Boolean = true) {
        val c = _controller.value ?: return
        val serverUrl = sessionProvider()?.serverUrl ?: return
        synchronized(registry) { items.forEach { registry[it.id] = it } }
        c.setMediaItems(items.map { toMediaItem(it, serverUrl) }, startIndex, 0)
        c.playWhenReady = playWhenReady
        c.prepare()
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
        if (wasEmpty) { c.prepare(); c.playWhenReady = true }
    }

    fun playPause() { _controller.value?.let { if (it.isPlaying) it.pause() else it.play() } }
    fun next() { _controller.value?.seekToNextMediaItem() }
    fun previous() { _controller.value?.seekToPreviousMediaItem() }
    fun seekTo(ms: Long) { _controller.value?.seekTo(ms) }
    fun skipToQueue(index: Int) { _controller.value?.seekTo(index, 0) }
    fun removeFromQueue(index: Int) { _controller.value?.removeMediaItem(index) }
    fun setShuffle(enabled: Boolean) { _controller.value?.shuffleModeEnabled = enabled }
    fun setRepeat(mode: Int) { _controller.value?.repeatMode = mode }

    companion object {
        private val itemJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }

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
