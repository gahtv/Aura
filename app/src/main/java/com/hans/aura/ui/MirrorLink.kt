package com.hans.aura.ui

import android.content.Context

/**
 * Mirror link — the LAN remote-control endpoint (port 8099).
 *
 * The implementation now lives in `com.hans.aura.remote.MirrorServer`; this
 * object is the source-compatible entry point the UI already calls, so
 * MainActivity / AppRoot / NowPlayingScreen compile unchanged while the server,
 * pairing and lifecycle disposal sit in their own package.
 *
 *   GET  /state                                 -> screen, playback, queue
 *   POST /navigate?screen=collection&itemId=…   -> 204
 *   POST /control?action=pause                  -> 204
 *   POST /input?key=down                        -> 204
 *   POST /pair/request?name=<device>            -> {"token":"…"} (after on-TV consent)
 *   GET  /health                                -> ok
 */
object MirrorLink {

    val state get() = com.hans.aura.remote.MirrorServer.state
    val nav get() = com.hans.aura.remote.MirrorServer.nav
    val pairing get() = com.hans.aura.remote.MirrorServer.pairing

    fun init(context: Context) = com.hans.aura.remote.MirrorServer.init(context)

    fun start() = com.hans.aura.remote.MirrorServer.start()

    /** Bind the activity that synthetic input is dispatched into. */
    fun bind(activity: android.app.Activity) = com.hans.aura.remote.MirrorServer.bind(activity)

    /** Release [activity] on finish (no-op once a newer Activity is bound). */
    fun unbind(activity: android.app.Activity) = com.hans.aura.remote.MirrorServer.unbind(activity)

    fun bindPlayer(p: com.hans.aura.ui.player.PlayerConnection) =
        com.hans.aura.remote.MirrorServer.bindPlayer(p)

    /** Release [p] when its owner disposes it. */
    fun unbindPlayer(p: com.hans.aura.ui.player.PlayerConnection) =
        com.hans.aura.remote.MirrorServer.unbindPlayer(p)

    fun deviceName(): String = com.hans.aura.remote.MirrorServer.deviceName()

    fun isPaired(): Boolean = com.hans.aura.remote.MirrorServer.isPaired()

    fun unpair() = com.hans.aura.remote.MirrorServer.unpair()

    /** Called by the on-TV prompt. */
    fun answerPairing(allowed: Boolean) = com.hans.aura.remote.MirrorServer.answerPairing(allowed)

    fun publish(screen: String, itemId: String? = null, title: String? = null) =
        com.hans.aura.remote.MirrorServer.publish(screen, itemId, title)

    /** The Now Playing screen has two faces; the remote mirrors whichever is up. */
    fun publishLyrics(visible: Boolean) = com.hans.aura.remote.MirrorServer.publishLyrics(visible)

    fun publishQueue(items: List<Triple<String, String, String>>, index: Int) =
        com.hans.aura.remote.MirrorServer.publishQueue(items, index)

    fun request(screen: String, itemId: String?) =
        com.hans.aura.remote.MirrorServer.request(screen, itemId)
}
