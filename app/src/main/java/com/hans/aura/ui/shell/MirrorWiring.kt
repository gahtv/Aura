package com.hans.aura.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.hans.aura.AuraApp
import com.hans.aura.ui.MirrorLink
import com.hans.aura.ui.nav.NavSpec
import com.hans.aura.ui.nav.NavState
import com.hans.aura.ui.player.PlayerConnection
import com.hans.aura.data.SourceQueries

/**
 * Mirror link wiring (ui/MirrorLink.kt): the remote never sees a keystroke — it
 * sees where we are and the queue, and asks us to navigate.
 *
 *  - publishes the current screen via [NavSpec.mirrorPublish] (remote
 *    vocabulary, not `Screen.key` internals),
 *  - publishes the queue + index,
 *  - honours remote navigation: root routes replace the stack, Now Playing /
 *    Queue push, album/artist routes fetch the item and jump to it.
 */
@Composable
internal fun MirrorWiring(
    app: AuraApp,
    player: PlayerConnection,
    nav: NavState
) {
    LaunchedEffect(player) { MirrorLink.bindPlayer(player) }

    val queue by player.queue.collectAsState()
    val queueIndex by player.queueIndex.collectAsState()
    LaunchedEffect(queue, queueIndex) {
        MirrorLink.publishQueue(
            queue.map { Triple(it.id, it.name, it.artistNames) },
            queueIndex
        )
    }

    // Publish where we are so a remote can draw its own native version of it.
    LaunchedEffect(nav.top) {
        val (kind, itemId) = NavSpec.mirrorPublish(nav.top)
        MirrorLink.publish(kind, itemId)
    }

    // Honour the navigation a remote asks for: remote opens album -> Aura opens
    // the album.
    LaunchedEffect(Unit) {
        MirrorLink.nav.collect { route ->
            val itemId = route.itemId
            if (NavSpec.routeNeedsItem(route.screen) && itemId != null) {
                runCatching { SourceQueries.item(itemId) }
                    .onSuccess { item ->
                        NavSpec.fromMirrorRoute(route.screen, itemId, item)?.let {
                            nav.jumpTo(it, bumpFocus = true)
                        }
                    }
                    .onFailure { android.util.Log.w("AURA_MIRROR", "nav fetch failed: $it") }
                return@collect
            }
            val target = NavSpec.fromMirrorRoute(route.screen, null, null) ?: return@collect
            if (NavSpec.isRootRoute(target)) {
                nav.jumpTo(target, bumpFocus = true)
            } else {
                nav.push(target)
            }
        }
    }
}
