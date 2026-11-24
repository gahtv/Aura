package com.hans.aura.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

private val LocalFocusNavigationTick = compositionLocalOf { 0 }

/** Pending handoffs yield to subsequent remote navigation, including in the top bar. */
@Composable
fun FocusHandoffHost(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    var navigationTick by remember { mutableIntStateOf(0) }
    Box(modifier.onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown) {
            when (event.key) {
                Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight,
                Key.DirectionCenter, Key.Enter, Key.Tab, Key.Back -> navigationTick++
            }
        }
        false
    }) {
        CompositionLocalProvider(LocalFocusNavigationTick provides navigationTick) { content() }
    }
}

/**
 * Tries now, then on up to eight frames. This allows restored lazy scroll state
 * and its next layout/composition passes to attach the item without a timed wait.
 * An immediate request can precede the owner's initial focus assignment, so
 * confirm it on the first frame as well. Returns true when a request is accepted;
 * a rejected request or an unattached target consumes another frame of the budget.
 * Callers must wait for focusable content before requesting a group.
 */
suspend fun FocusRequester.requestFocusOnFrame(maxFrames: Int = 8): Boolean {
    require(maxFrames >= 0)
    fun tryFocus(): Boolean = try {
        requestFocus()
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
 * [ready] must describe focusable content, not just an attached container.
 * Readiness changes after a completed handoff do not repeat it for this tick.
 * [cancelled] abandons this tick, e.g. a completed empty/error result. Later
 * navigation through [FocusHandoffHost] also abandons pending work.
 */
@Composable
fun RestoreFocusOnBack(
    focusTick: Int,
    containerRequester: FocusRequester,
    focusedId: String? = null,
    keeper: FocusKeeper? = null,
    ready: Boolean = true,
    cancelled: Boolean = false
) {
    val navigationTick = LocalFocusNavigationTick.current
    val active = LocalDestinationActive.current
    val entryNavigationTick = remember(focusTick) { navigationTick }
    var requested by remember(focusTick) { mutableStateOf(false) }
    LaunchedEffect(focusTick, ready, cancelled, navigationTick, active) {
        if (!active) return@LaunchedEffect
        if (cancelled || navigationTick != entryNavigationTick) requested = true
        if (focusTick <= 0 || !ready || requested) return@LaunchedEffect
        val target = focusedId?.let { id -> keeper?.requesterFor(id) }
        if (target?.requestFocusOnFrame() != true) containerRequester.requestFocusOnFrame()
        requested = true
    }
}

/**
 * Pushed screens on TV get no automatic focus — without this, a newly opened
 * screen has no focused item and D-pad presses do nothing until focus is
 * found by accident. Runs once per screen entry.
 * [ready] gates that one handoff until focusable content is composed; subsequent
 * refreshes do not reclaim focus. Use [shouldFocus] for entry, not loading state.
 * [cancelled] or subsequent remote navigation abandons this entry's handoff.
 */
@Composable
fun InitialFocusOnPush(
    shouldFocus: Boolean,
    requester: FocusRequester,
    ready: Boolean = true,
    cancelled: Boolean = false
) {
    val navigationTick = LocalFocusNavigationTick.current
    val active = LocalDestinationActive.current
    val entryNavigationTick = remember(shouldFocus) { navigationTick }
    var requested by remember(shouldFocus) { mutableStateOf(false) }
    LaunchedEffect(shouldFocus, ready, cancelled, navigationTick, active) {
        if (!active) return@LaunchedEffect
        if (cancelled || navigationTick != entryNavigationTick) requested = true
        if (!shouldFocus || !ready || requested) return@LaunchedEffect
        requester.requestFocusOnFrame()
        requested = true
    }
}
