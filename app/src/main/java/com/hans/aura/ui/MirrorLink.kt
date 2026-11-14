package com.hans.aura.ui

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import java.lang.ref.WeakReference
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * Mirror link: lets a remote control see which screen Aura is on and ask it to
 * navigate, so both ends can render the same place.
 *
 * Deliberately tiny — a hand-rolled HTTP/1.0 server on the LAN, no auth, no
 * dependencies. It answers two questions and accepts one command:
 *
 *   GET  /state                      -> {"screen":"collection","itemId":"…","lyrics":false,
 *                                        "queue":[…],"queueIndex":n}
 *   POST /navigate?screen=collection&itemId=…  -> 204
 *   POST /input?key=up|down|left|right|select|back|home|playpause -> 204
 *   GET  /health                     -> ok
 *
 * Input goes through the app itself (a synthetic KeyEvent dispatched into the
 * running activity), so no ADB, no USB debugging, nothing developer-ish.
 *
 * The remote never needs to know how Aura renders anything; it renders its own
 * native equivalent of the named screen.
 */
object MirrorLink {
    private const val TAG = "AURA_MIRROR"
    private const val PORT = 8099

    data class Snapshot(
        val screen: String,
        val itemId: String? = null,
        val title: String? = null,
        val lyrics: Boolean = false
    )

    data class Nav(val screen: String, val itemId: String? = null)

    private val _state = MutableStateFlow(Snapshot("home"))
    val state: StateFlow<Snapshot> = _state

    private val _nav = MutableSharedFlow<Nav>(extraBufferCapacity = 8)
    val nav: SharedFlow<Nav> = _nav

    @Volatile private var started = false
    @Volatile private var lyricsVisible = false
    @Volatile private var activityRef: WeakReference<android.app.Activity>? = null
    private val main = Handler(Looper.getMainLooper())

    private data class QueueEntry(val id: String, val title: String, val artist: String)
    @Volatile private var queue: List<QueueEntry> = emptyList()
    @Volatile private var queueIndex: Int = 0

    @Volatile private var player: com.hans.aura.ui.player.PlayerConnection? = null

    /** Bind the activity that synthetic input is dispatched into. */
    fun bind(activity: android.app.Activity) {
        activityRef = WeakReference(activity)
    }

    /** The endpoint that owns playback — the Connect half of this link. */
    fun bindPlayer(p: com.hans.aura.ui.player.PlayerConnection) {
        player = p
    }

    private fun control(action: String, query: Map<String, String>): Boolean {
        val p = player ?: return false
        val known = setOf("play", "pause", "playpause", "next", "prev", "seek", "skip", "shuffle", "repeat")
        if (action !in known) return false

        // Every PlayerConnection call must happen on the main thread — Media3
        // throws "Player is accessed on the wrong thread" from an HTTP worker,
        // and the request would hang instead of answering.
        main.post {
            runCatching {
                when (action) {
                    "play" -> if (!p.isPlaying.value) p.playPause()
                    "pause" -> if (p.isPlaying.value) p.playPause()
                    "playpause" -> p.playPause()
                    "next" -> p.next()
                    "prev" -> p.previous()
                    "seek" -> query["position"]?.toLongOrNull()?.let { p.seekTo(it) }
                    "skip" -> query["index"]?.toIntOrNull()?.let { p.skipToQueue(it) }
                    "shuffle" -> query["on"]?.let { p.setShuffle(it == "true") }
                    "repeat" -> query["mode"]?.toIntOrNull()?.let { p.setRepeat(it) }
                }
            }.onFailure { Log.w(TAG, "control $action failed: ${it.message}") }
        }
        Log.i(TAG, "control $action")
        return true
    }

    fun publishQueue(items: List<Triple<String, String, String>>, index: Int) {
        val next = items.take(300).map { QueueEntry(it.first, it.second, it.third) }
        if (next == queue && index == queueIndex) return
        queue = next
        queueIndex = index
        Log.i(TAG, "queue ${next.size} index=$index")
    }

    private fun dispatchKey(code: Int): Boolean {
        val activity = activityRef?.get() ?: return false
        main.post {
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        return true
    }

    private fun handleInput(key: String): Boolean {
        val code = when (key) {
            "up" -> KeyEvent.KEYCODE_DPAD_UP
            "down" -> KeyEvent.KEYCODE_DPAD_DOWN
            "left" -> KeyEvent.KEYCODE_DPAD_LEFT
            "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
            "select" -> KeyEvent.KEYCODE_DPAD_CENTER
            "back" -> KeyEvent.KEYCODE_BACK
            "playpause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            else -> null
        }
        // "home" is app-level: Android's HOME needs a system permission, and
        // leaving Aura is never what a music remote means by home.
        if (key == "home") {
            request("home", null)
            return true
        }
        if (code == null) return false
        return dispatchKey(code)
    }

    fun publish(screen: String, itemId: String? = null, title: String? = null) {
        val next = Snapshot(screen, itemId, title, lyricsVisible)
        if (_state.value != next) {
            _state.value = next
            Log.i(TAG, "state ${next.screen} item=${next.itemId ?: "-"} lyrics=${next.lyrics}")
        }
    }

    /** The Now Playing screen has two faces; the remote mirrors whichever is up. */
    fun publishLyrics(visible: Boolean) {
        lyricsVisible = visible
        val current = _state.value
        if (current.lyrics != visible) {
            _state.value = current.copy(lyrics = visible)
            Log.i(TAG, "lyrics=$visible")
        }
    }

    fun request(screen: String, itemId: String?) {
        val accepted = _nav.tryEmit(Nav(screen, itemId))
        Log.i(TAG, "nav request ${screen} item=${itemId ?: "-"} accepted=$accepted")
    }

    fun start() {
        if (started) return
        started = true
        Thread({
            try {
                ServerSocket(PORT).use { server ->
                    Log.i(TAG, "listening on 0.0.0.0:$PORT")
                    while (true) handle(server.accept())
                }
            } catch (t: Throwable) {
                Log.w(TAG, "server stopped: ${t.message}")
                started = false
            }
        }, "aura-mirror").apply { isDaemon = true }.start()
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            try {
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val out = s.getOutputStream().bufferedWriter()
                val line = reader.readLine() ?: return
                val parts = line.split(" ")
                val method = parts.getOrNull(0) ?: "GET"
                val target = parts.getOrNull(1) ?: "/"
                val path = target.substringBefore('?')
                val query = parseQuery(target.substringAfter('?', ""))

                when {
                    path == "/health" -> out.write("HTTP/1.0 200 OK\r\nContent-Type: text/plain\r\n\r\nok")
                    path == "/state" -> out.write(
                        "HTTP/1.0 200 OK\r\nContent-Type: application/json\r\nCache-Control: no-store\r\n\r\n" +
                            stateJson()
                    )
                    path == "/navigate" && method == "POST" -> {
                        request(query["screen"] ?: "home", query["itemId"])
                        out.write("HTTP/1.0 204 No Content\r\n\r\n")
                    }
                    path == "/control" && method == "POST" -> {
                        val ok = control(query["action"] ?: "", query)
                        out.write(
                            if (ok) "HTTP/1.0 204 No Content\r\n\r\n"
                            else "HTTP/1.0 400 Bad Request\r\n\r\n"
                        )
                    }
                    path == "/input" && method == "POST" -> {
                        val key = query["key"] ?: ""
                        val ok = handleInput(key)
                        Log.i(TAG, "input $key ok=$ok")
                        out.write(
                            if (ok) "HTTP/1.0 204 No Content\r\n\r\n"
                            else "HTTP/1.0 400 Bad Request\r\n\r\n"
                        )
                    }
                    else -> out.write("HTTP/1.0 404 Not Found\r\n\r\n")
                }
                out.flush()
            } catch (t: Throwable) {
                Log.w(TAG, "request failed: ${t.message}")
            }
        }
    }

    private fun stateJson(): String {
        val snap = _state.value
        val snapshotQueue = queue
        val snapshotIndex = queueIndex
        return buildJsonObject {
            put("screen", snap.screen)
            put("itemId", snap.itemId)
            put("title", snap.title)
            put("lyrics", snap.lyrics)
            put("queueIndex", snapshotIndex)
            put("playing", player?.isPlaying?.value ?: false)
            put("positionMs", player?.positionMs?.value ?: 0L)
            put("durationMs", player?.durationMs?.value ?: 0L)
            put("shuffle", player?.shuffleEnabled?.value ?: false)
            put("repeat", player?.repeatMode?.value ?: 0)
            put("trackId", player?.current?.value?.id)
            put("trackTitle", player?.current?.value?.name)
            put("trackArtist", player?.current?.value?.artistNames)
            put("queue", kotlinx.serialization.json.JsonArray(snapshotQueue.map {
                buildJsonObject {
                    put("id", it.id)
                    put("title", it.title)
                    put("artist", it.artist)
                }
            }))
        }.toString()
    }

    private fun parseQuery(raw: String): Map<String, String> =
        raw.split('&').filter { it.contains('=') }.associate { pair ->
            val key = pair.substringBefore('=')
            val value = URLDecoder.decode(pair.substringAfter('='), "UTF-8")
            key to value
        }
}
