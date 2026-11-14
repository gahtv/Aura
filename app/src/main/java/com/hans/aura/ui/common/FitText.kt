package com.hans.aura.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.MarqueeAnimationMode
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

/**
 * Title text that degrades gracefully on a 10-foot screen:
 *
 *  1. try every candidate size largest-first and keep the first that fits the
 *     available width (a 30-character title just gets a bit smaller);
 *  2. if even the smallest size overflows, keep it and marquee horizontally.
 *
 * Why not rely on ellipsis: on TV the title is the only thing identifying the
 * track, and "Song Name That Is Long Enou…" is useless. Why not shrink forever:
 * below ~24sp the title stops being readable across a room.
 *
 * [widthCap] is only an upper bound: the real width used for measuring and for
 * the marquee frame comes from BoxWithConstraints, i.e. whatever the parent
 * actually granted (a Row sibling can hand out less than the cap). Sizing to a
 * hard cap instead makes the node refuse to shrink and pushes its neighbours —
 * e.g. the top bar's Search/Settings buttons — off the screen.
 *
 * The marquee is time-based and needs no focus. `animationMode = Immediately`
 * is passed explicitly; the focus-driven modes never animate here because the
 * title itself is not a focusable element on TV.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AutoFitMarqueeText(
    text: String,
    widthCap: Dp,
    sizes: List<TextUnit>,
    color: Color,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null
) {
    BoxWithConstraints(modifier.widthIn(max = widthCap)) {
        val available: Dp = maxWidth // scope property: the width we were actually given
        val widthPx = with(LocalDensity.current) { available.roundToPx() }
        val measurer = rememberTextMeasurer()

        // Largest size that fits; null when even the smallest overflows.
        // Measure UNCONSTRAINED and compare widths: a constrained measure of a
        // single-line (softWrap = false) text clamps its reported size, so
        // didOverflowWidth stays false and everything looks like "it fits".
        val fit: TextUnit? = remember(text, widthPx, sizes) {
            sizes.firstOrNull { size ->
                measurer.measure(
                    text = text,
                    style = TextStyle(fontSize = size, fontWeight = fontWeight),
                    softWrap = false,
                    maxLines = 1
                ).size.width <= widthPx
            }
        }
        val size = fit ?: sizes.last()

        if (fit != null) {
            androidx.tv.material3.Text(
                text,
                color = color,
                fontSize = size,
                fontWeight = fontWeight,
                maxLines = 1,
                softWrap = false
            )
        } else {
            androidx.tv.material3.Text(
                text,
                color = color,
                fontSize = size,
                fontWeight = fontWeight,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Visible,
                modifier = Modifier
                    // Exact width of the granted slot: gives the marquee a frame
                    // to scroll inside without ever widening the parent.
                    .width(available)
                    .clipToBounds()
                    .basicMarquee(
                        animationMode = MarqueeAnimationMode.Immediately,
                        initialDelayMillis = 1200,
                        repeatDelayMillis = 1200
                    )
            )
        }
    }
}
