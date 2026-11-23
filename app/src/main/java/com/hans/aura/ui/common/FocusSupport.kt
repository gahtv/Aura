package com.hans.aura.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester

/**
 * Tries now, then on up to eight frames. This allows restored lazy scroll state
 * and its next layout/composition passes to attach the item without a timed wait.
 * Compose 1.7's Unit-returning request can run before the owner's initial focus
 * assignment, so confirm an immediate request on the first frame as well.
 */
suspend fun FocusRequester.requestFocusOnFrame(maxFrames: Int = 8): Boolean {
    require(maxFrames >= 0)
    fun tryFocus(): Boolean = try {
        requestFocus()
        true
    } catch (_: IllegalStateException) {
        false
    }
    val immediate = tryFocus()
    if (maxFrames == 0) return immediate
    repeat(maxFrames) {
        withFrameNanos { }
        if (tryFocus()) return true
    }
    return false
}

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
 * to the content group after allowing lazy layout to attach the saved item.
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
        val target = focusedId?.let { id -> keeper?.requesterFor(id) }
        if (target?.requestFocusOnFrame() == true) return@LaunchedEffect
        containerRequester.requestFocusOnFrame()
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
        requester.requestFocusOnFrame()
    }
}
