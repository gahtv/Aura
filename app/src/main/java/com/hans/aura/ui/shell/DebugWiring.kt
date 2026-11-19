package com.hans.aura.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.hans.aura.AuraApp
import com.hans.aura.ui.DebugNav
import com.hans.aura.ui.common.ContextMenuHost
import com.hans.aura.ui.common.ContextMenus
import com.hans.aura.ui.nav.NavSpec
import com.hans.aura.ui.nav.NavState
import com.hans.aura.ui.player.PlayerConnection

/**
 * Debug deep-link wiring (adb extras, see ui/DebugNav.kt): jump straight to a
 * screen; optional autoplay queues one random track first, which also triggers
 * the auto-push into Now Playing. Extra hooks drive the session-exit paths,
 * the long-press context menu and queue mutations so tests need no DPAD.
 */
@Composable
internal fun DebugWiring(
    app: AuraApp,
    player: PlayerConnection,
    nav: NavState,
    endSession: (action: String, forgetServer: Boolean) -> Unit
) {
    LaunchedEffect(Unit) {
        DebugNav.request.collect { link ->
            if (link == null) return@collect
            DebugNav.request.value = null
            // Debug hook: exercise the session-exit paths (sign in again /
            // change server / sign out) without driving the Settings DPAD.
            // Same code path as the buttons, minus the confirm dialog.
            link.auth?.let { auth ->
                when (auth) {
                    "signinagain" -> endSession("sign in again", false)
                    "changeserver" -> endSession("change server", true)
                    "signout" -> endSession("sign out", true)
                    // "qcstart" belongs to the login screen's Quick Connect
                    // hook (LoginScreen reads it while signed out); reaching
                    // here means the app is signed in, so there is nothing to do.
                    "qcstart" -> Unit
                    else -> android.util.Log.w("AURA_AUTH", "unknown aura_debug_auth=$auth")
                }
                return@collect
            }
            if (link.autoplay) {
                runCatching {
                    app.jellyfin.auth.session ?: return@collect
                    val result = com.hans.aura.data.SourceQueries.items(
                        includeItemTypes = "Audio",
                        recursive = true, sortBy = "Random", limit = 1, startIndex = 0
                    )
                    if (result.items.isNotEmpty()) player.playQueue(result.items, 0, resume = false)
                    android.util.Log.d(
                        "AURA_NAV",
                        "autoplay queued ${result.items.size} track(s): ${result.items.firstOrNull()?.name} id=${result.items.firstOrNull()?.id}"
                    )
                }.onFailure { android.util.Log.d("AURA_NAV", "autoplay failed: $it") }
            } else {
                NavSpec.fromDebugScreen(link.screen)?.let { nav.jumpTo(it) }
            }
            // Debug: open the long-press context menu for a specific item and
            // optionally auto-select one of its actions. Everything lands in
            // logcat (AURA_MENU / AURA_QUEUE) so tests need no screenshots.
            link.menuItem?.let { itemId ->
                kotlinx.coroutines.delay(1000)
                runCatching {
                    val session = app.jellyfin.auth.session ?: return@collect
                    val item = app.jellyfin.api.getItem(itemId = itemId, userId = session.userId)
                    val spec = if (item.type == "Audio") {
                        ContextMenus.forTrack(
                            app, player, this, item,
                            onOpenArtist = { nav.actions.onOpenArtist(it) },
                            onOpenCollection = { nav.actions.onOpenCollection(it) }
                        )
                    } else {
                        ContextMenus.forAlbum(
                            app, player, this, item,
                            onOpenArtist = { nav.actions.onOpenArtist(it) }
                        )
                    }
                    android.util.Log.d(
                        "AURA_MENU",
                        "open \"${spec.title}\" actions=${spec.actions.map { it.label }}"
                    )
                    ContextMenuHost.open(spec)
                    if (link.menuAction >= 0) {
                        kotlinx.coroutines.delay(500)
                        val action = spec.actions.getOrNull(link.menuAction)
                        android.util.Log.d("AURA_MENU", "select[" + link.menuAction + "]=" + (action?.label ?: "OUT OF RANGE"))
                        action?.onSelect?.invoke()
                        kotlinx.coroutines.delay(2000)
                        android.util.Log.d(
                            "AURA_QUEUE",
                            "queue=${player.queue.value.map { it.name }} index=${player.queueIndex.value}"
                        )
                    }
                }.onFailure { android.util.Log.d("AURA_MENU", "failed: $it") }
            }
            // Debug: drive a queue mutation without DPAD keys, so reordering
            // and clear-upcoming are verifiable from AURA_QUEUE logcat alone.
            link.queueAction?.let { op ->
                kotlinx.coroutines.delay(1500) // queue mirror settled
                android.util.Log.d(
                    "AURA_QUEUE",
                    "debug op=$op before=${player.queue.value.map { it.id }} index=${player.queueIndex.value}"
                )
                when {
                    op == "clear" -> player.clearUpcoming()
                    op.startsWith("move:") -> {
                        val parts = op.removePrefix("move:").split(',')
                        val from = parts.getOrNull(0)?.trim()?.toIntOrNull()
                        val to = parts.getOrNull(1)?.trim()?.toIntOrNull()
                        if (from != null && to != null) player.moveInQueue(from, to)
                        else android.util.Log.d("AURA_QUEUE", "debug move: bad args \"$op\"")
                    }
                    // Headless hooks for the playback batch: sleep timer (in
                    // seconds, so a 5s test is possible) and smart shuffle.
                    op.startsWith("sleep:") -> {
                        val secs = op.removePrefix("sleep:").trim().toIntOrNull() ?: 0
                        if (secs == 0) player.cancelSleepTimer() else player.startSleepTimer(secs)
                    }
                    op == "smart" -> player.applySmartOrder()
                    op == "normalorder" -> player.restoreNormalOrder()
                    else -> android.util.Log.d("AURA_QUEUE", "debug op unknown: $op")
                }
                kotlinx.coroutines.delay(1500)
                android.util.Log.d(
                    "AURA_QUEUE",
                    "debug after=${player.queue.value.map { it.id }} index=${player.queueIndex.value}"
                )
            }
        }
    }
}
