package com.hans.aura

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.hans.aura.ui.AppRoot
import com.hans.aura.ui.DebugNav
import com.hans.aura.ui.MirrorLink
import com.hans.aura.ui.theme.AuraTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // LAN mirror link: /state + /navigate for remote controls (ui/MirrorLink.kt).
        MirrorLink.start()
        MirrorLink.bind(this)
        // Debug deep links: adb am start --es aura_debug_screen <name>
        handleDebugIntent(intent)
        setContent {
            AuraTheme {
                AppRoot()
            }
        }
    }

    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        // Debug deep links must also fire when the activity is already running
        // (am start without force-stop delivers onNewIntent, not onCreate).
        intent = newIntent
        handleDebugIntent(newIntent)
    }

    /** Deferred debug extras, debuggable builds only (see ui/DebugNav.kt). */
    private fun handleDebugIntent(intent: Intent?) {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        DebugNav.postFromIntent(intent)
    }

    /**
     * BACK must always mean navigation (pop / exit), never Compose's internal
     * "move focus out of the group" behavior — which otherwise hijacks BACK
     * while a focus group (menu bar, search results) is focused.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK &&
            event.action == KeyEvent.ACTION_DOWN &&
            event.repeatCount == 0
        ) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
