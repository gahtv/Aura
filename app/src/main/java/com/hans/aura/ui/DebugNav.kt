package com.hans.aura.ui

import android.content.Intent
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
 *  - aura_debug_queue <op>    drive a queue mutation without DPAD keys:
 *    "move:<from>,<to>" or "clear" (clear upcoming)
 *  - aura_debug_auth <what>   end the session without a confirm dialog:
 *    "signinagain" (keeps the server URL) | "changeserver" | "signout"
 */
object DebugNav {
    data class Link(
        val screen: String,
        val autoplay: Boolean,
        val menuItem: String? = null,
        val menuAction: Int = -1,
        val queueAction: String? = null,
        val auth: String? = null
    )

    val request = MutableStateFlow<Link?>(null)

    fun post(
        screen: String,
        autoplay: Boolean = false,
        menuItem: String? = null,
        menuAction: Int = -1,
        queueAction: String? = null,
        auth: String? = null
    ) {
        request.value = Link(screen, autoplay, menuItem, menuAction, queueAction, auth)
    }

    /**
     * Parses the debug extras off a launch intent. Returns null when the intent
     * carries no `aura_debug_screen` — the shared entry point for both
     * onCreate and onNewIntent.
     */
    fun fromIntent(intent: Intent?): Link? {
        val screen = intent?.getStringExtra("aura_debug_screen") ?: return null
        return Link(
            screen = screen,
            autoplay = intent.getBooleanExtra("aura_debug_autoplay", false),
            menuItem = intent.getStringExtra("aura_debug_menu"),
            menuAction = intent.getIntExtra("aura_debug_menu_action", -1),
            queueAction = intent.getStringExtra("aura_debug_queue"),
            auth = intent.getStringExtra("aura_debug_auth")
        )
    }

    /** Posts the link carried by [intent], if any. */
    fun postFromIntent(intent: Intent?) {
        fromIntent(intent)?.let { request.value = it }
    }
}
