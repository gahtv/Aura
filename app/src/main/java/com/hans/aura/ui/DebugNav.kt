package com.hans.aura.ui

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Debug-only deep links: adb can launch the app directly on a named screen
 * (`--es aura_debug_screen tracks [--ez aura_debug_autoplay true]`), so test
 * setups don't have to be driven key-by-key. Set by MainActivity (debuggable
 * builds only); consumed once by AppRoot. Harmless no-op in release.
 */
object DebugNav {
    data class Link(val screen: String, val autoplay: Boolean)

    val request = MutableStateFlow<Link?>(null)

    fun post(screen: String, autoplay: Boolean = false) {
        request.value = Link(screen, autoplay)
    }
}
