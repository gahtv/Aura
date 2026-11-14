package com.hans.aura

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.hans.aura.ui.AppRoot
import com.hans.aura.ui.theme.AuraTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Debug deep links: adb am start --es aura_debug_screen <name>
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            intent.getStringExtra("aura_debug_screen")?.let {
                com.hans.aura.ui.DebugNav.post(it, intent.getBooleanExtra("aura_debug_autoplay", false))
            }
        }
        setContent {
            AuraTheme {
                AppRoot()
            }
        }
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
