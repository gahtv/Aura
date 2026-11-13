package com.hans.aura.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.hans.aura.data.AuthStore
import com.hans.aura.data.JellyfinItem
import com.hans.aura.ui.theme.Border
import com.hans.aura.ui.theme.Surface
import com.hans.aura.ui.theme.SurfaceHigh
import com.hans.aura.ui.theme.TextPrimary
import com.hans.aura.ui.theme.TextSecondary

/** D-pad-focusable square artwork card, Spotify-grid style. */
@Composable
fun ArtworkCard(
    item: JellyfinItem,
    serverUrl: String,
    size: Dp,
    badge: String? = null,
    focusKeeper: FocusKeeper? = null,
    onFocused: ((String) -> Unit)? = null,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .width(size)
            .then(
                if (focusKeeper != null) {
                    Modifier.focusRequester(focusKeeper.requesterFor(item.id))
                } else Modifier
            )
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused?.invoke(item.id)
            }
            .clickable { onClick() }
    ) {
        val bg by animateColorAsState(if (focused) SurfaceHigh else Surface, label = "card")
        Box(
            modifier = Modifier
                .aspectRatio(1f)
                .clip(RoundedCornerShape(if (focused) 6.dp else 4.dp))
                .background(bg)
                .padding(if (focused) 2.dp else 0.dp)
                .then(
                    // Unmistakable focus outline: accent ring on the artwork.
                    if (focused) Modifier.border(
                        width = 3.dp,
                        color = com.hans.aura.ui.theme.Accent,
                        shape = RoundedCornerShape(6.dp)
                    ) else Modifier
                )
        ) {
            val tag = item.primaryImageTag
            // Tracks often carry no Primary image of their own; fall back to the album art.
            val imageItem = when {
                tag != null -> item.id
                item.albumId != null -> item.albumId!!
                else -> null
            }
            if (imageItem != null) {
                Box {
                    AsyncImage(
                        model = AuthStore.imageUrl(serverUrl, imageItem, tag, maxWidth = 480),
                        contentDescription = item.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    badge?.let { label ->
                        Box(
                            Modifier
                                .align(Alignment.BottomEnd)
                                .padding(6.dp)
                                .background(Color(0xCC14100C), RoundedCornerShape(3.dp))
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        ) {
                            androidx.tv.material3.Text(
                                label,
                                color = com.hans.aura.ui.theme.Accent,
                                fontSize = 9.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                            )
                        }
                    }
                }
            } else {
                Box(Modifier.fillMaxSize().background(SurfaceHigh), Alignment.Center) {
                    androidx.tv.material3.Text(
                        item.name.take(1).uppercase(),
                        fontSize = 40.sp,
                        color = TextSecondary
                    )
                }
            }
        }
        androidx.tv.material3.Text(
            item.name,
            color = if (focused) TextPrimary else TextPrimary,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp)
        )
        androidx.tv.material3.Text(
            subtitleOf(item),
            color = TextSecondary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun subtitleOf(item: JellyfinItem): String = when (item.type) {
    "MusicAlbum" -> item.albumArtist ?: item.artistNames.ifBlank { "Album" }
    "MusicArtist" -> "Artist"
    "Playlist" -> "Playlist"
    else -> item.artistNames.ifBlank { "" }
}

/** One track row: index/title left, artist, duration right. */
@Composable
fun TrackRow(
    index: Int,
    track: JellyfinItem,
    isCurrent: Boolean,
    onClick: () -> Unit,
    focusKeeper: FocusKeeper? = null,
    onFocused: ((String) -> Unit)? = null,
    onRemove: (() -> Unit)? = null
) {
    var focused by remember { mutableStateOf(false) }
    val bg by animateColorAsState(
        when {
            isCurrent -> Color(0xFF2A2116)
            focused -> SurfaceHigh
            else -> Color.Transparent
        },
        label = "trackRow"
    )
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(bg)
            .then(
                if (focusKeeper != null) {
                    Modifier.focusRequester(focusKeeper.requesterFor(track.id))
                } else Modifier
            )
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused?.invoke(track.id)
            }
            .clickable { onClick() }
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left accent bar marks keyboard focus on rows.
        if (focused) {
            Box(
                Modifier
                    .width(4.dp)
                    .height(40.dp)
                    .background(com.hans.aura.ui.theme.Accent)
            )
            androidx.compose.foundation.layout.Spacer(Modifier.width(16.dp))
        }
        androidx.tv.material3.Text(
            text = if (isCurrent) "\u25B6" else index.toString(),
            color = if (isCurrent) com.hans.aura.ui.theme.Accent else TextSecondary,
            fontSize = 14.sp,
            modifier = Modifier.width(36.dp)
        )
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            androidx.tv.material3.Text(
                track.name,
                color = if (isCurrent) com.hans.aura.ui.theme.Accent else TextPrimary,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (track.artistNames.isNotBlank()) {
                androidx.tv.material3.Text(
                    track.artistNames,
                    color = TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        androidx.tv.material3.Text(
            formatDuration(track.durationMs),
            color = TextSecondary,
            fontSize = 13.sp,
            modifier = Modifier.padding(start = 24.dp)
        )
        if (onRemove != null) {
            androidx.tv.material3.Text(
                "\u2715",
                color = TextSecondary,
                fontSize = 16.sp,
                modifier = Modifier
                    .padding(start = 16.dp)
                    .clickable { onRemove() }
            )
        }
    }
}

/** Set while a full-screen overlay (bio popup, playlist picker) is open —
 *  the now-playing bar hides so overlays sit on top and keep focus. */
object OverlayVisibility {
    var dialogOpen by mutableStateOf(false)
}

/** Tracks which item last held focus in a screen so BACK can restore it exactly. */
class FocusKeeper {
    private val requesters = mutableMapOf<String, androidx.compose.ui.focus.FocusRequester>()

    fun requesterFor(id: String): androidx.compose.ui.focus.FocusRequester =
        synchronized(requesters) {
            requesters.getOrPut(id) { androidx.compose.ui.focus.FocusRequester() }
        }
}

/**
 * Pulls focus into a screen's content group after BACK navigation.
 * Restores the exact previously-focused item when known; otherwise falls back
 * to the content group. Delayed so the restored scroll position settles first.
 */
@Composable
fun RestoreFocusOnBack(
    focusTick: Int,
    containerRequester: androidx.compose.ui.focus.FocusRequester,
    focusedId: String? = null,
    keeper: FocusKeeper? = null
) {
    LaunchedEffect(focusTick) {
        if (focusTick <= 0) return@LaunchedEffect
        kotlinx.coroutines.delay(350)
        val target = focusedId?.let { id -> keeper?.requesterFor(id) }
        if (target != null) {
            val result = runCatching { target.requestFocus() }
            if (result.isSuccess) return@LaunchedEffect
        }
        runCatching { containerRequester.requestFocus() }
    }
}

/**
 * Pushed screens on TV get no automatic focus — without this, a newly opened
 * screen has no focused item and D-pad presses do nothing until focus is
 * found by accident. Runs once per screen entry.
 */
@Composable
fun InitialFocusOnPush(shouldFocus: Boolean, requester: androidx.compose.ui.focus.FocusRequester) {
    LaunchedEffect(shouldFocus) {
        if (!shouldFocus) return@LaunchedEffect
        kotlinx.coroutines.delay(150)
        repeat(5) {
            val ok = runCatching { requester.requestFocus() }.isSuccess
            if (ok) return@LaunchedEffect
            kotlinx.coroutines.delay(100)
        }
    }
}

fun formatDuration(ms: Long): String {
    val total = ms / 1000
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}
