@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.hans.aura.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hans.aura.ui.theme.TextPrimary
import com.hans.aura.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/**
 * Scrollable full-text popup for long artist/album bios.
 *
 * TV focus is TRAPPED inside the panel, the D-pad scrolls the text directly
 * (focus starts on the text), and BACK closes. No Close button — BACK is the
 * dismiss path on TV.
 */
@Composable
fun BioDialog(title: String, subtitle: String?, text: String, onDismiss: () -> Unit) {
    BackHandler { onDismiss() }
    com.hans.aura.ui.common.OverlayVisibility.dialogOpen = true
    val textFocus = remember { FocusRequester() }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(100)
        runCatching { textFocus.requestFocus() }
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { com.hans.aura.ui.common.OverlayVisibility.dialogOpen = false }
    }

    fun onTextKey(event: KeyEvent): Boolean {
        if (event.type != androidx.compose.ui.input.key.KeyEventType.KeyUp) return false
        val atTop = scroll.value <= 0f
        val atBottom = scroll.value >= scroll.maxValue
        return when (event.key) {
            Key.DirectionDown -> if (atBottom) false else {
                scope.launch { scroll.scrollBy(150f) }
                true
            }
            Key.DirectionUp -> if (atTop) false else {
                scope.launch { scroll.scrollBy(-150f) }
                true
            }
            else -> false
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xE60E0C0A))
    ) {
        Column(
            Modifier
                .align(Alignment.Center)
                .widthIn(max = 900.dp)
                .fillMaxHeight(0.85f)
                .clip(RoundedCornerShape(10.dp))
                .background(com.hans.aura.ui.theme.Surface)
                // Trap focus: the screen behind the dialog must never receive it.
                .focusProperties { exit = { FocusRequester.Cancel } }
                .padding(36.dp)
        ) {
            androidx.tv.material3.Text(title, fontSize = 26.sp, color = TextPrimary)
            subtitle?.let {
                androidx.tv.material3.Text(it, fontSize = 14.sp, color = TextSecondary)
            }
            Spacer(Modifier.height(20.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(scroll)
                    .focusRequester(textFocus)
                    .focusable()
                    .focusProperties {
                        up = FocusRequester.Cancel
                        down = FocusRequester.Cancel
                    }
                    .onKeyEvent(::onTextKey)
            ) {
                androidx.tv.material3.Text(
                    text,
                    fontSize = 15.sp,
                    color = TextPrimary,
                    lineHeight = 24.sp
                )
            }
        }
    }
}
