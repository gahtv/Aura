package com.hans.aura.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester

/** Set while a full-screen overlay (bio popup, playlist picker) is open —
 *  the now-playing bar hides so overlays sit on top and keep focus. */
object OverlayVisibility {
    var dialogOpen by mutableStateOf(false)
}

/** Tracks which item last held focus in a screen so BACK can restore it exactly. */
class FocusKeeper {
    private val requesters = mutableMapOf<String, FocusRequester>()

    fun requesterFor(id: String): FocusRequester =
        synchronized(requesters) {
            requesters.getOrPut(id) { FocusRequester() }
        }
}

/**
 * Pulls focus into a screen's content group after BACK navigation.
 * Restores the exact previously-focused item when known; otherwise falls back
 * to the content group. Delayed so the restored scroll position settles first.
 */
@Composable
fun RestoreFocusOnBack(
    focusTick: Int,
    containerRequester: FocusRequester,
    focusedId: String? = null,
    keeper: FocusKeeper? = null
) {
    LaunchedEffect(focusTick) {
        if (focusTick <= 0) return@LaunchedEffect
        kotlinx.coroutines.delay(350)
        val target = focusedId?.let { id -> keeper?.requesterFor(id) }
        if (target != null) {
            val result = runCatching { target.requestFocus() }
            if (result.isSuccess) return@LaunchedEffect
        }
        runCatching { containerRequester.requestFocus() }
    }
}

/**
 * Pushed screens on TV get no automatic focus — without this, a newly opened
 * screen has no focused item and D-pad presses do nothing until focus is
 * found by accident. Runs once per screen entry.
 */
@Composable
fun InitialFocusOnPush(shouldFocus: Boolean, requester: FocusRequester) {
    LaunchedEffect(shouldFocus) {
        if (!shouldFocus) return@LaunchedEffect
        kotlinx.coroutines.delay(150)
        repeat(5) {
            val ok = runCatching { requester.requestFocus() }.isSuccess
            if (ok) return@LaunchedEffect
            kotlinx.coroutines.delay(100)
        }
    }
}
