package com.hans.aura.ui.common

import androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/** Rows below the fold that may warm up; anything further never asks for a slot. */
const val MOTION_LOOKAHEAD_ROWS = 2

/** A fast scroll must come to rest this long before a new cover starts moving. */
const val MOTION_SETTLE_MS = 150L

/**
 * Flat item indices a grid may animate: every visible item plus [lookaheadRows]
 * whole rows below, debounced by [settleMs] so a fling does not start and stop
 * a decoder per frame.
 *
 * The lookahead is what makes the first row after a scroll start moving without
 * the user waiting for it; the debounce is what keeps it from asking at all
 * while the list is still travelling.
 */
@Composable
fun rememberGridMotionWindow(
    state: LazyGridState,
    columns: Int,
    lookaheadRows: Int = MOTION_LOOKAHEAD_ROWS,
    settleMs: Long = MOTION_SETTLE_MS
): Set<Int> {
    val target by remember(state, columns, lookaheadRows) {
        derivedStateOf { gridMotionWindow(state.layoutInfo, columns, lookaheadRows) }
    }
    var settled by remember(state) { mutableStateOf(emptySet<Int>()) }
    LaunchedEffect(state) {
        // collectLatest cancels the pending settle on every further scroll, so
        // the window only lands once the grid has been still for settleMs.
        snapshotFlow { target }.collectLatest { next ->
            if (next != settled) {
                delay(settleMs)
                if (next != settled) settled = next
            }
        }
    }
    return settled
}

/** Pure window maths, so it can be reasoned about (and tested) without Compose. */
fun gridMotionWindow(
    info: LazyGridLayoutInfo,
    columns: Int,
    lookaheadRows: Int
): Set<Int> {
    if (columns <= 0 || info.visibleItemsInfo.isEmpty()) return emptySet()
    val firstIndex = info.visibleItemsInfo.first().index
    val lastIndex = info.visibleItemsInfo.last().index
    val firstRow = firstIndex / columns
    val lastRow = lastIndex / columns
    val from = firstRow * columns
    val until = (lastRow + lookaheadRows + 1) * columns
    val lastItem = info.totalItemsCount - 1
    if (lastItem < 0) return emptySet()
    return (from..minOf(until - 1, lastItem)).toSet()
}

/**
 * Tracks whether one shelf row is inside the window, for surfaces that are a
 * plain scrolling `Column` of shelves (Home) and therefore have no lazy
 * layoutInfo to read.
 *
 * Attach [modifier] to the shelf and pass [visible] as its `animate` flag.
 * [lookahead] is the slack below/above the viewport that counts as "close
 * enough to warm up" — roughly two shelf rows.
 */
@Composable
fun rememberOnScreen(lookahead: Dp = 260.dp): OnScreen {
    var visible by remember { mutableStateOf(false) }
    val view = LocalView.current
    val lookaheadPx = with(LocalDensity.current) { lookahead.toPx() }
    val modifier = remember(view, lookaheadPx) {
        Modifier.onGloballyPositioned { coords ->
            val top = coords.positionInWindow().y
            val bottom = top + coords.size.height
            val height = view.height.toFloat()
            val now = height > 0f && top < height + lookaheadPx && bottom > -lookaheadPx
            // Guarded: onGloballyPositioned fires on every placement (i.e. every
            // scrolled pixel), and an unguarded write would recompose the shelf
            // on each of them.
            if (now != visible) visible = now
        }
    }
    return OnScreen(modifier, visible)
}

/** Result of [rememberOnScreen]: the modifier to attach and the current flag. */
class OnScreen internal constructor(val modifier: Modifier, val visible: Boolean)
