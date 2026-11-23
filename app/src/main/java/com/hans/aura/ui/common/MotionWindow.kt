package com.hans.aura.ui.common

import androidx.compose.foundation.lazy.LazyListLayoutInfo
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

/** Visible list items plus nearby items on either side, clamped to the list. */
fun listMotionWindow(info: LazyListLayoutInfo, lookaheadItems: Int = 2): Set<Int> {
    if (info.visibleItemsInfo.isEmpty() || info.totalItemsCount <= 0) return emptySet()
    val lookahead = lookaheadItems.coerceAtLeast(0)
    val first = info.visibleItemsInfo.minOf { it.index }
    val last = info.visibleItemsInfo.maxOf { it.index }
    val from = (first.toLong() - lookahead).coerceAtLeast(0).toInt()
    val through = (last.toLong() + lookahead).coerceAtMost((info.totalItemsCount - 1).toLong()).toInt()
    return (from..through).toSet()
}
