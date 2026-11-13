package com.hans.aura.playback

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.Player
import com.hans.aura.data.APP_VERSION
import com.hans.aura.data.CapabilitiesDto
import com.hans.aura.data.JellyfinClient
import com.hans.aura.data.JellyfinItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
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
    private val onPlayItems: (items: List<JellyfinItem>, mode: Int, startMs: Long) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val main = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null
    private var client: OkHttpClient? = null

    fun start() {
        val session = jellyfin.auth.session ?: return
        scope.launch { connectWithRetry(session.accessToken, session.deviceId, session.serverUrl) }
        // Server drops idle sockets after ~60s; announce liveness like official clients.
        scope.launch {
            while (true) {
                delay(30_000)
                ws?.send(buildJsonObject { put("MessageType", "KeepAlive") }.toString())
            }
        }
    }

    /** Raw POST — the Retrofit path didn't bind capabilities reliably on Jellyfin 12. */
    private fun postCapabilities(token: String, deviceId: String, serverUrl: String) {
        val body = "{\"PlayableMediaTypes\":[\"Audio\"]," +
            "\"SupportedCommands\":[\"Play\",\"Playstate\",\"SetVolume\",\"DisplayMessage\"," +
            "\"SetRepeatMode\",\"SetShuffleQueue\"]," +
            "\"SupportsMediaControl\":true,\"SupportsPersistentIdentifier\":true}"
        val req = Request.Builder()
            .url("$serverUrl/Sessions/Capabilities/Full")
            .header(
                "Authorization",
                "MediaBrowser Client=\"$APP_NAME\", Device=\"Android TV\", " +
                    "DeviceId=\"$deviceId\", Version=\"$APP_VERSION\", Token=\"$token\""
            )
            .post(okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), body))
            .build()
        client!!.newCall(req).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                Log.w(TAG, "capabilities failed: ${e.message}")
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                Log.i(TAG, "capabilities posted: ${response.code}")
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
        val auth = "MediaBrowser Client=\"$APP_NAME\", Device=\"Android TV\", " +
            "DeviceId=\"$deviceId\", Version=\"$APP_VERSION\", Token=\"$token\""
        // Reuse one client so reconnects don't pile up connection pools/threads.
        if (client == null) {
            client = OkHttpClient.Builder()
                .pingInterval(java.time.Duration.ofSeconds(30))
                .build()
        }
        val request = Request.Builder()
            .url(wsUrl)
            .header("Authorization", auth)
            .build()

        isSocketClosed = false
        ws = client!!.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "websocket connected")
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
                Log.w(TAG, "socket failure: ${t.message}", t)
                isSocketClosed = true
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "socket closed: code=$code reason=$reason")
                isSocketClosed = true
            }
        })
    }

    private fun handle(text: String) {
        val obj = runCatching { json.decodeFromString<JsonObject>(text) }.getOrNull() ?: return
        val type = obj["MessageType"]?.jsonPrimitive?.content ?: return
        if (type != "KeepAlive") Log.i(TAG, "ws message: ${text.take(300)}")
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
                Log.i(TAG, "remote play: $command $ids")
                scope.launch {
                    runCatching {
                        val userId = jellyfin.auth.session!!.userId
                        val items = ids.map { jellyfin.api.getItem(userId, it) }
                        when (command) {
                            "PlayNext" ->
                                // Enqueue after the current track without interrupting.
                                onPlayItems(items, MODE_INSERT_NEXT, startMs)
                            "PlayLast" ->
                                // Append at the end of the queue without interrupting.
                                onPlayItems(items, MODE_APPEND_END, startMs)
                            else -> onPlayItems(items, MODE_REPLACE, startMs)
                        }
                    }.onFailure { Log.w(TAG, "remote play failed", it) }
                }
            }

            "Playstate" -> {
                val cmd = data?.get("Command")?.jsonPrimitive?.content
                Log.i(TAG, "remote playstate: $cmd")
                when (cmd) {
                    "Play", "Unpause" -> onMainThread { player.play() }
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
                Log.i(TAG, "remote general command: $name args=$args")
                fun arg(key: String): String? =
                    args?.get(key)?.jsonPrimitive?.content
                when (name) {
                    "Play" -> onMainThread { player.play() }
                    "Unpause" -> onMainThread { player.play() }
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
                        Log.i(TAG, "remote set repeat: $mode")
                        onMainThread { player.repeatMode = mode }
                    }
                    "SetShuffleQueue" -> {
                        // Arguments: {Shuffle: "true"|"false"}
                        val enabled = (arg("Shuffle") ?: arg("Enabled") ?: "false")
                            .equals("true", true)
                        Log.i(TAG, "remote set shuffle: $enabled")
                        onMainThread { player.shuffleModeEnabled = enabled }
                    }
                    "SetVolume" -> {
                        val vol = arg("Volume")?.toIntOrNull() ?: return
                        onMainThread { player.volume = (vol / 100f).coerceIn(0f, 1f) }
                    }
                    "VolumeUp" -> onMainThread { player.volume = (player.volume + 0.1f).coerceIn(0f, 1f) }
                    "VolumeDown" -> onMainThread { player.volume = (player.volume - 0.1f).coerceIn(0f, 1f) }
                }
            }
        }
    }

    private fun togglePlayPause() {
        if (player.isPlaying) player.pause() else player.play()
    }

    private fun stopPlayback() {
        player.stop()
        player.clearMediaItems()
    }

    /** ExoPlayer requires all calls on its application thread; dispatch there. */
    private fun onMainThread(block: () -> Unit) {
        main.post(block)
    }

    fun stop() {
        ws?.close(1001, "app closing")
        ws = null
        isSocketClosed = true
    }

    companion object {
        /** Where remote "Play" items go in the queue. */
        const val MODE_REPLACE = 0        // Replace queue, start at first item
        const val MODE_INSERT_NEXT = 1    // Insert after the current track ("Play Next")
        const val MODE_APPEND_END = 2     // Append at the end of the queue ("Play Last")
        private const val TAG = "AuraRemote"
        private const val APP_NAME = "Aura"
    }
}
