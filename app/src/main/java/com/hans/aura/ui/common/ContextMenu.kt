@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.hans.aura.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.composed
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hans.aura.AuraApp
import com.hans.aura.data.JellyfinItem
import com.hans.aura.ui.theme.AuraColors
import com.hans.aura.ui.theme.AuraShapes
import com.hans.aura.ui.theme.AuraSizes
import com.hans.aura.ui.theme.AuraSpacing
import com.hans.aura.ui.theme.AuraTypography
import com.hans.aura.ui.theme.Accent
import com.hans.aura.ui.theme.FocusOutline
import com.hans.aura.ui.theme.Surface
import com.hans.aura.ui.theme.SurfaceHigh
import com.hans.aura.ui.theme.TextPrimary
import com.hans.aura.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/** One row in a context menu. `icon` is a Material Symbols drawable id
 *  (ic_*); when null the row is text-only. */
data class ContextAction(
    val label: String,
    val icon: Int? = null,
    val onSelect: () -> Unit
)

/** What a long-press opens: title, subtitle and the available actions. */
data class ContextMenuSpec(
    val title: String,
    val subtitle: String? = null,
    val actions: List<ContextAction>
)

/** Global host: any screen can open the menu; AppRoot renders it on top. */
object ContextMenuHost {
    var current by mutableStateOf<ContextMenuSpec?>(null)
    var pickerTracks by mutableStateOf<List<JellyfinItem>?>(null)

    fun open(spec: ContextMenuSpec) {
        android.util.Log.d("AURA_MENU", "host.open \"${spec.title}\" actions=${spec.actions.map { it.label }}")
        current = spec
    }
    fun openPicker(tracks: List<JellyfinItem>) {
        android.util.Log.d("AURA_MENU", "picker open for ${tracks.size} track(s): ${tracks.map { it.name }}")
        pickerTracks = tracks
    }
    fun close() { current = null; pickerTracks = null }
}

/**
 * Detects a held OK/ENTER on a focused element and fires onLongPress once per
 * hold. Detection is time-based (~500ms of a pressed-down key) so it works
 * with real remotes (key repeats) AND input injectors that hold down+up
 * without emitting repeats. Plain presses pass through to click handling; the
 * release after a hold is consumed so the menu doesn't ALSO trigger the click.
 */
fun Modifier.contextMenuKeys(onLongPress: (() -> Unit)?): Modifier = composed {
    var fired by remember { mutableStateOf(false) }
    var holdJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    onPreviewKeyEvent { event ->
        if (onLongPress == null) return@onPreviewKeyEvent false
        val k = event.key
        if (k != Key.DirectionCenter && k != Key.Enter) return@onPreviewKeyEvent false
        val repeat = (event.nativeKeyEvent as android.view.KeyEvent).repeatCount
        when (event.type) {
            androidx.compose.ui.input.key.KeyEventType.KeyDown -> when {
                repeat == 0 -> {
                    // Start the hold timer; a release before it fires is a plain click.
                    holdJob?.cancel()
                    fired = false
                    holdJob = scope.launch {
                        kotlinx.coroutines.delay(500)
                        fired = true
                        onLongPress?.invoke()
                    }
                    false
                }
                repeat >= 1 && !fired -> {
                    // Real remote hold: repeats confirm the hold immediately.
                    holdJob?.cancel()
                    fired = true
                    onLongPress?.invoke()
                    true
                }
                else -> true // swallow further repeats while the menu is up
            }
            androidx.compose.ui.input.key.KeyEventType.KeyUp -> {
                holdJob?.cancel()
                if (fired) { fired = false; true } else false
            }
            else -> false
        }
    }
}

/**
 * Full-screen context menu on long-press. Focus is trapped inside, the first
 * action is focused on open, BACK dismisses.
 */
@Composable
fun ContextMenuOverlay(spec: ContextMenuSpec, onDismiss: () -> Unit) {
    BackHandler { onDismiss() }
    OverlayVisibility.dialogOpen = true
    val firstFocus = remember { FocusRequester() }
    // The hold that opened the menu may still be in progress: its release (and
    // any remaining key repeats) would land on the focused first action and
    // click it. Ignore OK for a grace window after opening.
    val openedAt = remember { android.os.SystemClock.uptimeMillis() }
    // Long enough to outlast the held key's release (long-press fires ~500ms
    // in; holds routinely release at 700-900ms), so only a deliberate SECOND
    // press reaches the focused action.
    val okGraceMillis = 1000L
    val swallowOk = Modifier.onPreviewKeyEvent { event ->
        val k = event.key
        val withinGrace = android.os.SystemClock.uptimeMillis() - openedAt < okGraceMillis
        if (withinGrace && (k == Key.DirectionCenter || k == Key.Enter)) {
            android.util.Log.d("AURA_MENU", "grace-swallowed ${event.type}")
            true
        } else false
    }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(100)
        repeat(5) {
            val ok = runCatching { firstFocus.requestFocus() }.isSuccess
            if (ok) return@LaunchedEffect
            kotlinx.coroutines.delay(100)
        }
    }
    DisposableEffect(Unit) { onDispose { OverlayVisibility.dialogOpen = false } }

    Box(Modifier.fillMaxSize().background(AuraColors.Scrim).then(swallowOk)) {
        Column(
            Modifier
                .align(Alignment.Center)
                .widthIn(max = AuraSizes.contextMenuWidth)
                .clip(RoundedCornerShape(AuraShapes.md))
                .background(Surface)
                .focusProperties { exit = { FocusRequester.Cancel } }
                .padding(AuraSpacing.lg)
        ) {
            androidx.tv.material3.Text(spec.title, fontSize = AuraTypography.title, color = TextPrimary,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            spec.subtitle?.let {
                androidx.tv.material3.Text(it, fontSize = AuraTypography.label, color = TextSecondary,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(AuraSpacing.md))
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .fillMaxHeight(0.7f)
            ) {
                spec.actions.forEachIndexed { i, action ->
                    MenuAction(
                        label = action.label,
                        icon = action.icon,
                        modifier = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier
                    ) {
                        android.util.Log.d("AURA_MENU", "select[$i]=${action.label}")
                        onDismiss()
                        action.onSelect()
                    }
                    Spacer(Modifier.height(AuraSpacing.xxs))
                }
            }
        }
    }
}

/**
 * AppRoot-level hosts: the long-press menu itself and the playlist picker it
 * can open ("Add to playlist" reuses the existing picker dialog). Rendered as
 * the last child of the root Box so they draw over everything, incl. the
 * now-playing pill in the top bar.
 */
@Composable
fun ContextMenuHosts(app: AuraApp) {
    ContextMenuHost.current?.let { spec ->
        ContextMenuOverlay(spec) { ContextMenuHost.current = null }
    }
    ContextMenuHost.pickerTracks?.let { tracks ->
        OverlayVisibility.dialogOpen = true
        DisposableEffect(Unit) { onDispose { OverlayVisibility.dialogOpen = false } }
        com.hans.aura.ui.playlist.PlaylistPickerDialog(
            app = app,
            tracksToAdd = tracks,
            onDismiss = { ContextMenuHost.pickerTracks = null }
        )
    }
}

/**
 * One context-menu action. Focus has to be loud on TV — the old tv-material
 * Button only tinted its container, which read as "focus is somewhere" — so a
 * focused row gets a filled background, a 2dp neutral outline and an accent bar
 * on the leading edge.
 */
@Composable
private fun MenuAction(
    label: String,
    icon: Int? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(AuraShapes.card)
    Row(
        modifier
            .fillMaxWidth()
            .height(AuraSizes.minTarget)
            .clip(shape)
            .background(if (focused) SurfaceHigh else Color.Transparent)
            .then(
                if (focused) Modifier.border(AuraSizes.focusBorder, FocusOutline, shape) else Modifier
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() }
            .padding(horizontal = AuraSpacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(AuraSizes.rowAccentBarWidth)
                .height(AuraSpacing.lg)
                .background(
                    if (focused) Accent else Color.Transparent,
                    RoundedCornerShape(AuraShapes.xs)
                )
        )
        Spacer(Modifier.width(AuraSpacing.sm))
        if (icon != null) {
            androidx.compose.material3.Icon(
                painter = androidx.compose.ui.res.painterResource(icon),
                contentDescription = null,
                tint = if (focused) Accent else TextSecondary,
                modifier = Modifier.size(AuraSizes.iconSmall)
            )
            Spacer(Modifier.width(AuraSpacing.sm))
        }
        androidx.tv.material3.Text(
            label,
            fontSize = AuraTypography.subtitle,
            color = if (focused) TextPrimary else TextSecondary
        )
    }
}
