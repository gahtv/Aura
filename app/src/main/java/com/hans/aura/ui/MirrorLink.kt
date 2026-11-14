package com.hans.aura.ui

import android.util.Log
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
 *   GET  /state                      -> {"screen":"collection","itemId":"…","lyrics":false}
 *   POST /navigate?screen=collection&itemId=…  -> 204
 *   GET  /health                     -> ok
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
        return buildJsonObject {
            put("screen", snap.screen)
            put("itemId", snap.itemId)
            put("title", snap.title)
            put("lyrics", snap.lyrics)
        }.toString()
    }

    private fun parseQuery(raw: String): Map<String, String> =
        raw.split('&').filter { it.contains('=') }.associate { pair ->
            val key = pair.substringBefore('=')
            val value = URLDecoder.decode(pair.substringAfter('='), "UTF-8")
            key to value
        }
}
