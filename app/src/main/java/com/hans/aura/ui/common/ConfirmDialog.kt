@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package com.hans.aura.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hans.aura.ui.theme.Border
import com.hans.aura.ui.theme.Surface
import com.hans.aura.ui.theme.TextPrimary
import com.hans.aura.ui.theme.TextSecondary

/**
 * Destructive-action confirmation, TV-first.
 *
 * Mandatory for anything that can strand the user (change server / sign in
 * again): a broken login is unrecoverable on a TV without adb. Rendered at the
 * SCREEN ROOT by the caller and it sets [OverlayVisibility.dialogOpen] while
 * composed so the top bar / now-playing pill cannot draw over it or steal
 * focus. Focus starts on Cancel and is trapped inside the panel — a dialog that
 * opens with nothing focused is dead on a remote (OK does nothing).
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    cancelLabel: String = "Cancel"
) {
    // BACK dismisses the dialog instead of popping the screen underneath.
    androidx.activity.compose.BackHandler { onDismiss() }

    // The now-playing bar and top bar gate their render on this flag.
    DisposableEffect(Unit) {
        OverlayVisibility.dialogOpen = true
        onDispose { OverlayVisibility.dialogOpen = false }
    }

    val cancelFocus = remember { FocusRequester() }
    // A dialog with no focused element is dead on arrival: the cursor request
    // also fails silently before the requester attaches, hence the retry loop.
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(100)
        repeat(6) {
            if (runCatching { cancelFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            kotlinx.coroutines.delay(100)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xCC0A0806)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .widthIn(max = 640.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Surface)
                .border(1.dp, Border, RoundedCornerShape(10.dp))
                // Trap the cursor: without this DOWN escapes to the content
                // behind the scrim and the user ends up in the settings list.
                .focusProperties { exit = { FocusRequester.Cancel } }
                .padding(32.dp)
        ) {
            androidx.tv.material3.Text(title, fontSize = 24.sp, color = TextPrimary)
            Spacer(Modifier.height(12.dp))
            androidx.tv.material3.Text(message, fontSize = 15.sp, color = TextSecondary)
            Spacer(Modifier.height(28.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                androidx.tv.material3.Button(
                    onClick = onDismiss,
                    modifier = Modifier.focusRequester(cancelFocus)
                ) {
                    androidx.tv.material3.Text(cancelLabel)
                }
                androidx.tv.material3.Button(onClick = onConfirm) {
                    androidx.tv.material3.Text(confirmLabel)
                }
            }
        }
    }
}
