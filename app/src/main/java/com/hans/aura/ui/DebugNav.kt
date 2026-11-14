package com.hans.aura.ui

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Debug-only deep links: adb can launch the app directly on a named screen
 * (`--es aura_debug_screen tracks [--ez aura_debug_autoplay true]`), so test
 * setups don't have to be driven key-by-key. Set by MainActivity (debuggable
 * builds only); consumed once by AppRoot. Harmless no-op in release.
 *
 * Extras beyond the screen name:
 *  - aura_debug_autoplay      queue one random track first
 *  - aura_debug_menu <itemId> open the long-press context menu for that item
 *  - aura_debug_menu_action N select the Nth menu action automatically
 *    (-1 = open the menu but don't select anything)
 */
object DebugNav {
    data class Link(
        val screen: String,
        val autoplay: Boolean,
        val menuItem: String? = null,
        val menuAction: Int = -1
    )

    val request = MutableStateFlow<Link?>(null)

    fun post(
        screen: String,
        autoplay: Boolean = false,
        menuItem: String? = null,
        menuAction: Int = -1
    ) {
        request.value = Link(screen, autoplay, menuItem, menuAction)
    }
}
