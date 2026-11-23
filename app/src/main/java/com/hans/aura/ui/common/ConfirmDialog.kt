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
import com.hans.aura.ui.theme.AuraColors
import com.hans.aura.ui.theme.AuraShapes
import com.hans.aura.ui.theme.AuraSizes
import com.hans.aura.ui.theme.AuraSpacing
import com.hans.aura.ui.theme.AuraTypography
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
        cancelFocus.requestFocusOnFrame()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(AuraColors.DialogScrim),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .widthIn(max = AuraSizes.dialogWidth)
                .clip(AuraShapes.dialog)
                .background(Surface)
                .border(AuraSizes.hairline, Border, AuraShapes.dialog)
                // Trap the cursor: without this DOWN escapes to the content
                // behind the scrim and the user ends up in the settings list.
                .focusProperties { exit = { FocusRequester.Cancel } }
                .padding(AuraSpacing.xl)
        ) {
            androidx.tv.material3.Text(title, fontSize = AuraTypography.title, color = TextPrimary)
            Spacer(Modifier.height(AuraSpacing.sm))
            androidx.tv.material3.Text(message, fontSize = AuraTypography.bodySmall, color = TextSecondary)
            Spacer(Modifier.height(AuraSpacing.lg))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AuraSpacing.md)
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
