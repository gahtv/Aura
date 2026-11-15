package com.hans.aura.remote

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import com.hans.aura.data.DeviceNames
import com.hans.aura.util.AuraLog
import com.hans.aura.util.AuraTag
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.ref.WeakReference
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Mirror link server: lets a remote control see which screen Aura is on, read
 * and drive the queue, and ask Aura to navigate.
 *
 *   GET  /state                                 -> screen, playback, queue
 *   POST /navigate?screen=collection&itemId=…   -> 204
 *   POST /control?action=pause                  -> 204
 *   POST /input?key=down                        -> 204
 *   POST /pair/request?name=<device>            -> {"token":"…"} (after on-TV consent)
 *   GET  /health                                -> ok
 *
 * Pairing follows the USB-debugging model: a device asks, the TV shows a prompt
 * naming the request, and only an explicit Allow mints a token. Everything but
 * /health and /pair requires that token in `X-Aura-Token`.
 *
 * Lifecycle: [init] from the app container (application context, device name,
 * stored token), [start] from the Activity, [bind] the Activity for synthetic
 * input and [bindPlayer] once a player exists. [unbind] / [unbindPlayer] clear
 * those references so a finishing Activity is never dispatched into and a dead
 * PlayerConnection is never read.
 */
object MirrorServer {
    private const val PORT = 8099
    private const val PREFS = "aura_mirror"
    private const val PAIR_TIMEOUT_MS = 90_000L

    data class Snapshot(
        val screen: String,
        val itemId: String? = null,
        val title: String? = null,
        val lyrics: Boolean = false
    )

    data class Nav(val screen: String, val itemId: String? = null)
    data class PairingRequest(val name: String)

    private val _state = MutableStateFlow(Snapshot("home"))
    val state: StateFlow<Snapshot> = _state

    private val _nav = MutableSharedFlow<Nav>(extraBufferCapacity = 8)
    val nav: SharedFlow<Nav> = _nav

    /** Non-null while the TV is showing the allow/deny prompt. */
    private val _pairing = MutableStateFlow<PairingRequest?>(null)
    val pairing: StateFlow<PairingRequest?> = _pairing

    @Volatile private var pendingPair: CompletableDeferred<Boolean>? = null

    @Volatile private var started = false
    @Volatile private var lyricsVisible = false
    @Volatile private var activityRef: WeakReference<android.app.Activity>? = null
    @Volatile private var token: String? = null
    private var appContext: Context? = null
    private val main = Handler(Looper.getMainLooper())
    private val prefsLock = Any()

    private data class QueueEntry(val id: String, val title: String, val artist: String)
    @Volatile private var queue: List<QueueEntry> = emptyList()
    @Volatile private var queueIndex: Int = 0

    @Volatile private var player: com.hans.aura.ui.player.PlayerConnection? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        DeviceNames.init(context.applicationContext)
        synchronized(prefsLock) {
            token = context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("token", null)
        }
        AuraLog.i(AuraTag.MIRROR, "device=${DeviceNames.get()} paired=${token != null}")
    }

    /** Bind the activity that synthetic input is dispatched into. */
    fun bind(activity: android.app.Activity) {
        activityRef = WeakReference(activity)
    }

    /** Release [activity] on finish; a different (newer) Activity keeps binding. */
    fun unbind(activity: android.app.Activity) {
        if (activityRef?.get() === activity) activityRef = null
    }

    /** The endpoint that owns playback — the Connect half of this link. */
    fun bindPlayer(p: com.hans.aura.ui.player.PlayerConnection) {
        player = p
    }

    /** Release [p] when its owner disposes it (Activity UI disposal). */
    fun unbindPlayer(p: com.hans.aura.ui.player.PlayerConnection) {
        if (player === p) player = null
    }

    fun deviceName(): String = DeviceNames.get()

    fun isPaired(): Boolean = token != null

    fun unpair() {
        token = null
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.remove("token")?.apply()
        AuraLog.i(AuraTag.MIRROR, "unpaired")
    }

    /** Called by the on-TV prompt. */
    fun answerPairing(allowed: Boolean) {
        pendingPair?.complete(allowed)
        pendingPair = null
        _pairing.value = null
    }

    fun publish(screen: String, itemId: String? = null, title: String? = null) {
        val next = Snapshot(screen, itemId, title, lyricsVisible)
        if (_state.value != next) {
            _state.value = next
            AuraLog.i(
                AuraTag.MIRROR,
                "state ${next.screen} item=${next.itemId ?: "-"} lyrics=${next.lyrics}"
            )
        }
    }

    /** The Now Playing screen has two faces; the remote mirrors whichever is up. */
    fun publishLyrics(visible: Boolean) {
        lyricsVisible = visible
        val current = _state.value
        if (current.lyrics != visible) {
            _state.value = current.copy(lyrics = visible)
            AuraLog.i(AuraTag.MIRROR, "lyrics=$visible")
        }
    }

    fun publishQueue(items: List<Triple<String, String, String>>, index: Int) {
        val next = items.take(300).map { QueueEntry(it.first, it.second, it.third) }
        if (next == queue && index == queueIndex) return
        queue = next
        queueIndex = index
        AuraLog.i(AuraTag.MIRROR, "queue ${next.size} index=$index")
    }

    fun request(screen: String, itemId: String?) {
        val accepted = _nav.tryEmit(Nav(screen, itemId))
        AuraLog.i(
            AuraTag.MIRROR,
            "nav request $screen item=${itemId ?: "-"} accepted=$accepted"
        )
    }

    fun start() {
        if (started) return
        started = true
        Thread({
            try {
                ServerSocket(PORT).use { server ->
                    AuraLog.i(AuraTag.MIRROR, "listening on 0.0.0.0:$PORT")
                    while (true) handle(server.accept())
                }
            } catch (t: Throwable) {
                AuraLog.w(AuraTag.MIRROR, "server stopped: ${t.message}")
                started = false
            }
        }, "aura-mirror").apply { isDaemon = true }.start()
    }

    // ---------------- HTTP ----------------

    private fun handle(socket: Socket) {
        socket.use { s ->
            try {
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val out = s.getOutputStream().bufferedWriter()

                val requestLine = reader.readLine() ?: return
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] =
                        line.substring(idx + 1).trim()
                }

                val parts = requestLine.split(" ")
                val method = parts.getOrNull(0) ?: "GET"
                val target = parts.getOrNull(1) ?: "/"
                val path = target.substringBefore('?')
                val query = parseQuery(target.substringAfter('?', ""))

                fun reply(code: Int, body: String = "", type: String = "application/json") {
                    out.write("HTTP/1.0 $code\r\nContent-Type: $type\r\nCache-Control: no-store\r\n" +
                        "Content-Length: ${body.toByteArray().size}\r\n\r\n$body")
                }

                val open = path == "/health" || path == "/pair/request"
                // An unpaired device must not be able to do anything: comparing
                // a missing header against a null token would otherwise let
                // null == null through.
                val authorised = token != null && headers["x-aura-token"] == token
                if (!open && !authorised) {
                    AuraLog.w(AuraTag.MIRROR, "rejected $path: bad token")
                    reply(401, """{"error":"unauthorized"}""")
                    out.flush()
                    return
                }

                when {
                    path == "/health" -> reply(200, "ok", "text/plain")

                    path == "/pair/request" && method == "POST" -> {
                        val name = query["name"]?.takeIf { it.isNotBlank() } ?: "Unknown device"
                        val issued = promptAndPair(name)
                        if (issued == null) reply(403, """{"error":"denied"}""")
                        else reply(200, """{"token":"$issued","device":"${deviceName()}"}""")
                    }

                    path == "/state" -> reply(200, stateJson())

                    path == "/navigate" && method == "POST" -> {
                        request(query["screen"] ?: "home", query["itemId"])
                        reply(204)
                    }

                    path == "/control" && method == "POST" -> {
                        val ok = control(query["action"] ?: "", query)
                        reply(if (ok) 204 else 400)
                    }

                    path == "/input" && method == "POST" -> {
                        val key = query["key"] ?: ""
                        val ok = handleInput(key)
                        AuraLog.i(AuraTag.MIRROR, "input $key ok=$ok")
                        reply(if (ok) 204 else 400)
                    }

                    else -> reply(404)
                }
                out.flush()
            } catch (t: Throwable) {
                AuraLog.w(AuraTag.MIRROR, "request failed: ${t.message}")
            }
        }
    }

    /**
     * Blocks the calling HTTP thread while the TV shows the prompt. Times out to
     * a denial, so a remote that is ignored never ends up paired by accident.
     */
    private fun promptAndPair(name: String): String? {
        val deferred = CompletableDeferred<Boolean>()
        pendingPair = deferred
        _pairing.value = PairingRequest(name)
        AuraLog.i(AuraTag.MIRROR, "pairing request from $name")

        val allowed = runBlocking { withTimeoutOrNull(PAIR_TIMEOUT_MS) { deferred.await() } } ?: false
        _pairing.value = null
        if (!allowed) {
            AuraLog.i(AuraTag.MIRROR, "pairing denied for $name")
            return null
        }

        val issued = UUID.randomUUID().toString().replace("-", "")
        token = issued
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.putString("token", issued)?.apply()
        AuraLog.i(AuraTag.MIRROR, "paired $name")
        return issued
    }

    private fun stateJson(): String {
        val snap = _state.value
        val snapshotQueue = queue
        val snapshotIndex = queueIndex
        return buildJsonObject {
            put("deviceName", deviceName())
            put("screen", snap.screen)
            put("itemId", snap.itemId)
            put("title", snap.title)
            put("lyrics", snap.lyrics)
            put("queueIndex", snapshotIndex)
            put("queue", JsonArray(snapshotQueue.map {
                buildJsonObject {
                    put("id", it.id)
                    put("title", it.title)
                    put("artist", it.artist)
                }
            }))
            put("playing", player?.isPlaying?.value ?: false)
            put("positionMs", player?.positionMs?.value ?: 0L)
            put("durationMs", player?.durationMs?.value ?: 0L)
            put("shuffle", player?.shuffleEnabled?.value ?: false)
            put("repeat", player?.repeatMode?.value ?: 0)
            put("trackId", player?.current?.value?.id)
            put("trackTitle", player?.current?.value?.name)
            put("trackArtist", player?.current?.value?.artistNames)
        }.toString()
    }

    // ---------------- playback / input ----------------

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
            }.onFailure { AuraLog.w(AuraTag.MIRROR, "control $action failed: ${it.message}") }
        }
        AuraLog.i(AuraTag.MIRROR, "control $action")
        return true
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

    private fun parseQuery(raw: String): Map<String, String> =
        raw.split('&').filter { it.contains('=') }.associate { pair ->
            val key = pair.substringBefore('=')
            val value = URLDecoder.decode(pair.substringAfter('='), "UTF-8")
            key to value
        }
}
