package com.hans.aura.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hans.aura.ui.theme.TextPrimary
import com.hans.aura.ui.theme.TextSecondary
import kotlinx.coroutines.delay

/**
 * Failure surface for a screen whose data could not be loaded.
 *
 * A TV has no pointer: the Retry button requests focus on entry, because a pane
 * with no focused element is dead on arrival and the D-pad would have nowhere
 * to land. Cursor styling follows the house language — light grey ring
 * (0xFFD6CCBE) over a lifted fill (0xFF3E362D) — and amber stays reserved for
 * "you are here".
 */
@Composable
fun ErrorPane(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null
) {
    val retryFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(10) {
            if (runCatching { retryFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(100)
        }
    }
    Column(
        modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        androidx.tv.material3.Text(
            message,
            fontSize = 20.sp,
            color = TextPrimary,
            textAlign = TextAlign.Center
        )
        detail?.takeIf { it.isNotBlank() }?.let { text ->
            Spacer(Modifier.height(8.dp))
            androidx.tv.material3.Text(
                text,
                fontSize = 13.sp,
                color = TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(20.dp))
        ErrorRetryButton(onRetry, Modifier.focusRequester(retryFocus))
    }
}

/** Retry button: 48dp minimum height, loud grey focus ring. */
@Composable
private fun ErrorRetryButton(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(if (focused) Color(0xFF3E362D) else Color(0xFF2A2520))
            .border(3.dp, if (focused) Color(0xFFD6CCBE) else Color.Transparent, shape)
            // onFocusChanged MUST precede clickable or the ring never renders.
            .onFocusChanged { focused = it.isFocused }
            .clickable { onRetry() }
            .padding(horizontal = 30.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        androidx.tv.material3.Text("Retry", fontSize = 16.sp, color = TextPrimary)
    }
}
