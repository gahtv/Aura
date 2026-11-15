package com.hans.aura.ui.shell

import com.hans.aura.AuraApp
import com.hans.aura.data.AtmosCatalog
import com.hans.aura.data.AuthStore
import com.hans.aura.data.LibraryCache
import com.hans.aura.data.PagedLibrary
import com.hans.aura.ui.player.PlayerConnection
import com.hans.aura.ui.theme.PaletteCache

/**
 * Every way out of a session funnels through here (sign in again, change
 * server, sign out). Order: stop playback and disconnect the controller FIRST —
 * the service owns ExoPlayer, so a live controller keeps the old server
 * streaming under the login screen — then evict everything keyed by its library
 * data, then drop the credentials.
 *
 * Mixing two servers' item ids in one cache renders the wrong artwork and the
 * wrong tracklists, and PaletteCache is keyed by URL, so it survives a server
 * swap with colliding item ids unless it is cleared here too.
 */
internal fun endSession(
    app: AuraApp,
    player: PlayerConnection,
    action: String,
    forgetServer: Boolean,
    onSignedOut: () -> Unit
) {
    val oldUrl = app.jellyfin.auth.session?.serverUrl ?: app.jellyfin.auth.lastServerUrl
    val oldHost = AuthStore.host(oldUrl)
    val playerItems = player.teardown()
    val library = LibraryCache.size
    val paged = PagedLibrary.size
    val atmos = AtmosCatalog.size
    val palette = PaletteCache.size
    LibraryCache.clear()
    PagedLibrary.clear()
    AtmosCatalog.clear()
    PaletteCache.clear()
    if (forgetServer) app.jellyfin.auth.clearServer() else app.jellyfin.auth.clearSession()
    val evicted = "evicted library=$library paged=$paged atmos=$atmos " +
        "palette=$palette playerItems=$playerItems"
    android.util.Log.i(
        "AURA_AUTH",
        when (action) {
            "change server" ->
                "change server: old=$oldHost -> cleared (prefill=none); $evicted"
            "sign in again" ->
                "sign in again: kept server=$oldHost token=cleared " +
                    "(prefill=$oldHost); $evicted"
            else -> "$action: server=$oldHost token=cleared; $evicted"
        }
    )
    onSignedOut()
}
