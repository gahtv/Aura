package com.hans.aura.playback

import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import com.hans.aura.data.APP_VERSION
import com.hans.aura.data.CapabilitiesDto
import com.hans.aura.data.JellyfinClient
import com.hans.aura.data.toDomainItem
import com.hans.aura.model.Track
import com.hans.aura.player.controller.QUEUE_ITEM_PARALLELISM
import com.hans.aura.player.controller.fetchQueueItems
import com.hans.aura.util.AuraLog
import com.hans.aura.util.AuraTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.MediaType.Companion.toMediaTypeOrNull

/**
 * Makes Aura a controllable Jellyfin session:
 *  - announces remote-control capabilities (shows up under "Play On"/remote)
 *  - holds the server websocket and executes Play/Playstate commands
 *    (play now/next from Jellyfin web, pause, stop, seek, next/prev).
 *
 * All player mutations are dispatched to the player's application thread —
 * ExoPlayer throws "accessed on the wrong thread" otherwise, which killed
 * every remote command except the ones routed through runOnMain.
 */
class PlaybackRemote(
    private val jellyfin: JellyfinClient,
    private val player: Player,
    private val onPlayItems:
        (items: List<Track>, mode: Int, startMs: Long, startIndex: Int) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queueItemPermits = Semaphore(QUEUE_ITEM_PARALLELISM)
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val main = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null
    private var client: OkHttpClient? = null

    fun start() {
        val session = jellyfin.auth.session ?: return
        // One coroutine owns both the retry loop and the keepalive, so stop()
        // (scope cancellation) takes the reconnect loop AND the liveness ping
        // with it. A remote that is never started, or is stopped, cannot leave a
        // zombie socket, a keepalive pinging into the void, or a retry waking up
        // later to reconnect.
        scope.launch {
            launch {
                // Server drops idle sockets after ~60s; announce liveness like
                // official clients.
                while (true) {
                    delay(30_000)
                    ws?.send(buildJsonObject { put("MessageType", "KeepAlive") }.toString())
                }
            }
            connectWithRetry(session.accessToken, session.deviceId, session.serverUrl)
        }
    }

    /** Raw POST — the Retrofit path didn't bind capabilities reliably on Jellyfin 12. */
    private fun postCapabilities(token: String, deviceId: String, serverUrl: String) {
        val body = "{\"PlayableMediaTypes\":[\"Audio\"]," +
            "\"SupportedCommands\":[\"Play\",\"Playstate\",\"DisplayMessage\"," +
            "\"SetRepeatMode\",\"SetShuffleQueue\"]," +
            "\"SupportsMediaControl\":true,\"SupportsPersistentIdentifier\":true}"
        val req = Request.Builder()
            .url("$serverUrl/Sessions/Capabilities/Full")
            .header(
                "Authorization",
                "MediaBrowser Client=\"$APP_NAME\", Device=\"${com.hans.aura.data.DeviceNames.get()}\", " +
                    "DeviceId=\"$deviceId\", Version=\"$APP_VERSION\", Token=\"$token\""
            )
            .post(body.toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        val http = client ?: return
        http.newCall(req).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                AuraLog.w(AuraTag.REMOTE, "capabilities failed: ${e.message}")
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                AuraLog.i(AuraTag.REMOTE, "capabilities posted: ${response.code}")
                response.close()
            }
        })
    }

    private suspend fun connectWithRetry(token: String, deviceId: String, serverUrl: String) {
        var attempt = 0
        while (true) {
            connect(token, deviceId, serverUrl)
            // Block until this socket dies, then back off before reconnecting.
            while (!isSocketClosed) delay(2_000)
            attempt++
            delay(minOf(attempt * 5_000L, 30_000L))
        }
    }

    private var isSocketClosed = true

    private fun connect(token: String, deviceId: String, serverUrl: String) {
        val wsUrl = serverUrl
            .replace("https://", "wss://")
            .replace("http://", "ws://")
            .trimEnd('/') + "/socket?api_key=$token&deviceId=$deviceId"
        val auth = "MediaBrowser Client=\"$APP_NAME\", Device=\"${com.hans.aura.data.DeviceNames.get()}\", " +
            "DeviceId=\"$deviceId\", Version=\"$APP_VERSION\", Token=\"$token\""
        // Reuse one client so reconnects don't pile up connection pools/threads.
        val http = client ?: OkHttpClient.Builder()
            .pingInterval(java.time.Duration.ofSeconds(30))
            .build()
            .also { client = it }
        val request = Request.Builder()
            .url(wsUrl)
            .header("Authorization", auth)
            .build()

        isSocketClosed = false
        ws = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                AuraLog.i(AuraTag.REMOTE, "websocket connected")
                isSocketClosed = false
                // Subscribe to session messages like the official clients; the
                // server attaches the remote-control controller to the session.
                webSocket.send(buildJsonObject {
                    put("MessageType", "SessionsStart")
                    put("Data", "0,1500,3000")
                }.toString())
                postCapabilities(token, deviceId, serverUrl)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handle(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                AuraLog.w(AuraTag.REMOTE, "socket failure: ${t.message}", t)
                isSocketClosed = true
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                AuraLog.i(AuraTag.REMOTE, "socket closed: code=$code reason=$reason")
                isSocketClosed = true
            }
        })
    }

    private fun handle(text: String) {
        val obj = runCatching { json.decodeFromString<JsonObject>(text) }.getOrNull() ?: return
        val type = obj["MessageType"]?.jsonPrimitive?.content ?: return
        if (type != "KeepAlive") AuraLog.i(AuraTag.REMOTE, "ws message: ${text.take(300)}")
        val data = obj["Data"] as? JsonObject

        when (type) {
            "KeepAlive" -> ws?.send(buildJsonObject { put("MessageType", "KeepAlive") }.toString())

            "Play" -> {
                // Server sends {ItemIds: [...], PlayCommand: "PlayNow"|"PlayNext"|"PlayLast"}
                val ids = data?.get("ItemIds")?.jsonArray
                    ?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
                    ?: data?.get("Items")?.jsonArray
                        ?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
                    ?: return
                val command = data?.get("PlayCommand")?.jsonPrimitive?.content
                    ?: data?.get("Command")?.jsonPrimitive?.content ?: "PlayNow"
                val startTicks = data?.get("StartPositionTicks")?.jsonPrimitive?.content
                    ?.toLongOrNull() ?: 0L
                val startMs = startTicks / 10_000
                // When the sender plays an ALBUM (Jellyfin web: click a track in
                // an album, then "Play"), the server expands it into the album's
                // full track list and puts the chosen track's POSITION in
                // StartIndex. Ignoring that made Aura always start at track 1
                // and the user had to skip manually.
                val startIndex = data?.get("StartIndex")?.jsonPrimitive?.content
                    ?.toIntOrNull() ?: 0
                AuraLog.i(AuraTag.REMOTE, "remote play: $command startIndex=$startIndex $ids")
                scope.launch {
                    try {
                        // The session can be dropped while the socket lingers
                        // (sign-out) — never assert it into a crash.
                        val userId = jellyfin.auth.session?.userId ?: return@launch
                        val items = fetchQueueItems(ids, queueItemPermits) { id ->
                            jellyfin.api.getItem(itemId = id, userId = userId).toDomainItem() as? Track
                                ?: error("Remote item is not playable audio: $id")
                        }
                        when (command) {
                            "PlayNext" ->
                                // Enqueue after the current track without interrupting.
                                onPlayItems(items, MODE_INSERT_NEXT, startMs, 0)
                            "PlayLast" ->
                                // Append at the end of the queue without interrupting.
                                onPlayItems(items, MODE_APPEND_END, startMs, 0)
                            else -> onPlayItems(items, MODE_REPLACE, startMs, startIndex)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AuraLog.w(AuraTag.REMOTE, "remote play failed", e)
                    }
                }
            }

            "Playstate" -> {
                val cmd = data?.get("Command")?.jsonPrimitive?.content
                AuraLog.i(AuraTag.REMOTE, "remote playstate: $cmd")
                when (cmd) {
                    "Play", "Unpause" -> onMainThread { player.playPrepared() }
                    "Pause" -> onMainThread { player.pause() }
                    "PlayPause" -> onMainThread { togglePlayPause() }
                    "Stop" -> onMainThread { stopPlayback() }
                    "NextTrack" -> onMainThread { player.seekToNextMediaItem() }
                    "PreviousTrack" -> onMainThread { player.seekToPreviousMediaItem() }
                    "Rewind" -> onMainThread { player.seekBack() }
                    "FastForward" -> onMainThread { player.seekForward() }
                    "Seek" -> {
                        val ticks = data?.get("SeekPositionTicks")?.jsonPrimitive?.content
                            ?.toLongOrNull()
                        if (ticks != null) onMainThread { player.seekTo(ticks / 10_000) }
                    }
                }
            }

            "GeneralCommand" -> {
                // Wire shape: {Name, ControllingUserId, Arguments:{arg:"value"}}.
                // Some senders/older servers put the args in "Data" instead, either
                // as a dictionary or as a JSON-encoded string of one — accept all.
                val name = data?.get("Name")?.jsonPrimitive?.content
                val args: JsonObject? = data?.get("Arguments") as? JsonObject
                    ?: data?.get("Data") as? JsonObject
                    ?: data?.get("Data")?.jsonPrimitive?.content?.let { raw ->
                        runCatching { json.decodeFromString<JsonObject>(raw) }.getOrNull()
                    }
                AuraLog.i(AuraTag.REMOTE, "remote general command: $name args=$args")
                fun arg(key: String): String? =
                    args?.get(key)?.jsonPrimitive?.content
                when (name) {
                    "Play" -> onMainThread { player.playPrepared() }
                    "Unpause" -> onMainThread { player.playPrepared() }
                    "PlayState", "PlayPause" -> onMainThread { togglePlayPause() }
                    "Pause" -> onMainThread { player.pause() }
                    "Stop" -> onMainThread { stopPlayback() }
                    "NextTrack" -> onMainThread { player.seekToNextMediaItem() }
                    "PreviousTrack" -> onMainThread { player.seekToPreviousMediaItem() }
                    "SetRepeatMode" -> {
                        // Arguments: {RepeatMode: "RepeatNone"|"RepeatAll"|"RepeatOne"}
                        val mode = when (arg("RepeatMode") ?: (args?.keys?.firstOrNull())) {
                            "RepeatOne" -> Player.REPEAT_MODE_ONE
                            "RepeatAll" -> Player.REPEAT_MODE_ALL
                            else -> Player.REPEAT_MODE_OFF
                        }
                        AuraLog.i(AuraTag.REMOTE, "remote set repeat: $mode")
                        onMainThread { player.repeatMode = mode }
                    }
                    "SetShuffleQueue" -> {
                        // Arguments: {Shuffle: "true"|"false"}
                        val enabled = (arg("Shuffle") ?: arg("Enabled") ?: "false")
                            .equals("true", true)
                        AuraLog.i(AuraTag.REMOTE, "remote set shuffle: $enabled")
                        onMainThread { player.shuffleModeEnabled = enabled }
                    }
                    // Volume is deliberately not remote-controllable: the AVR owns level
                    // and the bitstream must stay at unity gain. Ignore (and say so in
                    // logcat) instead of silently applying a remote attenuation.
                    "SetVolume", "VolumeUp", "VolumeDown" ->
                        AuraLog.i(AuraTag.REMOTE, "remote $name ignored (volume is AVR-controlled)")
                }
            }
        }
    }

    private fun togglePlayPause() {
        if (player.isPlaying) player.pause() else player.playPrepared()
    }

    private fun stopPlayback() {
        player.stop()
        player.clearMediaItems()
    }

    /** ExoPlayer requires all calls on its application thread; dispatch there. */
    private fun onMainThread(block: () -> Unit) {
        main.post(block)
    }

    /**
     * Full shutdown, called from PlaybackService.onDestroy().
     *
     * Cancelling the scope FIRST is what stops `connectWithRetry` from waking
     * 5s later and reconnecting a zombie WebSocket after the service is gone;
     * the socket close is only the polite half. The client's dispatcher pool and
     * connection pool are shut down too, otherwise their threads outlive the
     * service and keep the process's network stack alive.
     */
    fun stop() {
        scope.cancel()
        runCatching { ws?.close(1001, "app closing") }
        ws = null
        isSocketClosed = true
        client?.let { c ->
            runCatching { c.dispatcher.executorService.shutdown() }
            runCatching { c.connectionPool.evictAll() }
        }
        client = null
    }

    companion object {
        /** Where remote "Play" items go in the queue. */
        const val MODE_REPLACE = 0        // Replace queue, start at first item
        const val MODE_INSERT_NEXT = 1    // Insert after the current track ("Play Next")
        const val MODE_APPEND_END = 2     // Append at the end of the queue ("Play Last")
        private const val APP_NAME = "Aura"
    }
}
