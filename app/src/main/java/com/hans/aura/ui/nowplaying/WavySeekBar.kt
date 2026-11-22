package com.hans.aura.ui.nowplaying

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import com.hans.aura.ui.theme.AuraSizes
import com.hans.aura.ui.theme.Border
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

@Composable
internal fun WavySeekBar(
    progress: Float,
    isPlaying: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    onSeek: (Long) -> Unit = {}
) {
    val fraction = if (progress.isNaN()) 0f else progress.coerceIn(0f, 1f)
    var focused by remember { mutableStateOf(false) }
    var phase by remember { mutableFloatStateOf(0f) }
    val wavePath = remember { Path() }
    val amplitude by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0f,
        animationSpec = tween(500, easing = FastOutSlowInEasing),
        label = "seekAmplitude"
    )
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            var previous = withFrameNanos { it }
            while (true) {
                withFrameNanos { now ->
                    phase = (phase + (now - previous) / 1_000_000_000f) % 1f
                    previous = now
                }
            }
        }
    }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(AuraSizes.wavySeekBarHeight)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                val delta = when (event.key) {
                    Key.DirectionLeft -> -10_000L
                    Key.DirectionRight -> 10_000L
                    else -> return@onPreviewKeyEvent false
                }
                if (event.type == KeyEventType.KeyDown) onSeek(delta)
                true
            }
            .semantics {
                contentDescription = "Playback position"
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
            }
            .focusable()
    ) {
        val stroke = AuraSizes.seekStroke.toPx()
        val radius = stroke / 2
        val start = radius
        val end = size.width - radius
        if (end <= start) return@Canvas
        val centerY = size.height / 2
        val point = start + (end - start) * fraction
        val gap = AuraSizes.seekGap.toPx()
        val wavelength = AuraSizes.seekWavelength.toPx()
        val waveHeight = AuraSizes.seekAmplitude.toPx() * amplitude
        val activeEnd = if (focused) point - gap - stroke else point
        if (activeEnd > start) {
            wavePath.rewind()
            var x = start
            while (true) {
                val taper = ((x - start) / (wavelength / 2)).coerceIn(0f, 1f)
                val y = centerY + sin(((x - start) / wavelength - phase) * 2 * PI).toFloat() * waveHeight * taper
                if (x == start) wavePath.moveTo(x, y) else wavePath.lineTo(x, y)
                if (x >= activeEnd) break
                x = min(x + 4f, activeEnd)
            }
            drawPath(wavePath, accent, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        val inactiveStart = point + gap + stroke
        if (inactiveStart < end) {
            drawLine(Border, Offset(inactiveStart, centerY), Offset(end, centerY), stroke, StrokeCap.Round)
        }
        drawCircle(accent, radius, Offset(end, centerY))
        if (focused) {
            val halfHandle = (AuraSizes.seekHandleHeight.toPx() - stroke) / 2
            drawLine(accent, Offset(point, centerY - halfHandle), Offset(point, centerY + halfHandle), stroke, StrokeCap.Round)
        }
    }
}
