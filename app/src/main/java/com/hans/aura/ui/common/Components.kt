@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.hans.aura.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import androidx.tv.material3.MaterialTheme
import com.hans.aura.R
import com.hans.aura.data.AuthStore
import com.hans.aura.data.JellyfinItem
import com.hans.aura.ui.theme.AuraColors
import com.hans.aura.ui.theme.AuraShapes
import com.hans.aura.ui.theme.AuraSizes
import com.hans.aura.ui.theme.AuraSpacing
import com.hans.aura.ui.theme.AuraTypography
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
    // Optional per-screen tint (artist pages derive it from the artist image);
    // defaults to the app-wide amber so no other screen changes.
    badgeColor: Color = com.hans.aura.ui.theme.Accent,
    accent: Color = com.hans.aura.ui.theme.Accent,
    focusKeeper: FocusKeeper? = null,
    onFocused: ((String) -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    // Liked state for album cards (null = draw no heart, e.g. artists).
    favorite: Boolean? = null,
    onToggleFavorite: (() -> Unit)? = null,
    // Opt-in motion cover. Off by default: every existing call site keeps a
    // still cover, and only the browse surfaces pass true inside their
    // visible window. A card that loses the motion budget draws the same still
    // image it would have drawn anyway, so there is no pop-in either way.
    animate: Boolean = false,
    // Rank used by the budget to prefer on-screen rows over prefetch rows.
    motionPriority: Int = 0,
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
            .contextMenuKeys(onLongPress)
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
                .clip(RoundedCornerShape(if (focused) AuraShapes.card else AuraShapes.xs))
                .background(bg)
                .padding(if (focused) AuraSizes.focusBorder else 0.dp)
                .then(
                    // Unmistakable focus outline: accent ring on the artwork.
                    if (focused) Modifier.border(
                        width = AuraSizes.focusBorderLoud,
                        color = accent,
                        shape = RoundedCornerShape(AuraShapes.card)
                    ) else Modifier
                )
        ) {
            val tag = item.primaryImageTag
            // Tracks often carry no Primary image of their own; fall back to the album art.
            val imageItem = when {
                tag != null -> item.id
                else -> item.albumId
            }
            if (imageItem != null) {
                Box {
                    val still = AuthStore.imageUrl(serverUrl, imageItem, tag, maxWidth = 480)
                    if (animate) {
                        // Same still image, with the motion loop over it when the
                        // budget grants a slot. Losing the slot changes nothing
                        // on screen: the poster is what was drawn before.
                        MotionArtwork(
                            itemId = imageItem,
                            posterModel = still,
                            contentDescription = item.name,
                            budgeted = true,
                            motionPriority = motionPriority,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        AsyncImage(
                            model = still,
                            contentDescription = item.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    badge?.let { label ->
                        Box(
                            Modifier
                                .align(Alignment.BottomEnd)
                                .padding(AuraSpacing.xxs)
                                .background(
                                    AuraColors.BadgeScrim,
                                    RoundedCornerShape(AuraShapes.xs)
                                )
                                .padding(horizontal = AuraSpacing.xxs, vertical = 2.dp)
                        ) {
                            androidx.tv.material3.Text(
                                label,
                                color = badgeColor,
                                fontSize = AuraTypography.caption,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    // Liked albums: heart badge on the cover, ONLY when liked.
                    // Drawing the hollow heart on every unfavourited cover put a
                    // grey glyph over the artwork of the whole grid (user-reported);
                    // favouriting lives in the long-press menu.
                    if (favorite == true) {
                        HeartToggle(
                            favorite = favorite,
                            accent = accent,
                            interactive = false,
                            modifier = Modifier.align(Alignment.TopEnd).padding(AuraSpacing.xxs)
                        )
                    }
                }
            } else {
                Box(Modifier.fillMaxSize().background(SurfaceHigh), Alignment.Center) {
                    androidx.tv.material3.Text(
                        item.name.take(1).uppercase(),
                        fontSize = AuraTypography.display,
                        color = TextSecondary
                    )
                }
            }
        }
        androidx.tv.material3.Text(
            item.name,
            color = TextPrimary,
            fontSize = AuraTypography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = AuraSpacing.xs)
        )
        androidx.tv.material3.Text(
            subtitleOf(item),
            color = TextSecondary,
            fontSize = AuraTypography.caption,
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
    atmos: Boolean = false,
    // Per-screen tint (artist pages); app amber elsewhere.
    accent: Color = com.hans.aura.ui.theme.Accent,
    focusKeeper: FocusKeeper? = null,
    onFocused: ((String) -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    // Liked state (null = draw no heart). Kept after every existing parameter
    // so no call site's trailing lambda can bind to it by accident.
    favorite: Boolean? = null,
    onToggleFavorite: (() -> Unit)? = null
) {
    var focused by remember { mutableStateOf(false) }
    val bg by animateColorAsState(
        when {
            // Tinted by the screen's accent so the "now playing" row belongs to
            // an album whose colour isn't the app amber.
            isCurrent -> accent.copy(alpha = 0.16f)
            focused -> SurfaceHigh
            else -> Color.Transparent
        },
        label = "trackRow"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(AuraSizes.trackRowHeight)
            .background(bg)
            .then(
                if (focusKeeper != null) {
                    Modifier.focusRequester(focusKeeper.requesterFor(track.id))
                } else Modifier
            )
            .contextMenuKeys(onLongPress)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused?.invoke(track.id)
            }
            .clickable { onClick() }
            .padding(horizontal = AuraSpacing.lg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left accent bar marks keyboard focus on rows.
        if (focused) {
            Box(
                Modifier
                    .width(AuraSizes.rowAccentBarWidth)
                    .height(AuraSizes.rowAccentBarHeight)
                    .background(accent)
            )
            Spacer(Modifier.width(AuraSpacing.md))
        }
        androidx.tv.material3.Text(
            text = if (isCurrent) "\u25B6" else index.toString(),
            color = if (isCurrent) accent else TextSecondary,
            fontSize = AuraTypography.bodySmall,
            modifier = Modifier.width(AuraSizes.trackIndexWidth)
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.tv.material3.Text(
                    track.name,
                    color = if (isCurrent) accent else TextPrimary,
                    fontSize = AuraTypography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (atmos) {
                    Spacer(Modifier.width(AuraSpacing.xs))
                    AtmosChip(color = accent)
                }
            }
            if (track.artistNames.isNotBlank()) {
                androidx.tv.material3.Text(
                    track.artistNames,
                    color = TextSecondary,
                    fontSize = AuraTypography.caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        androidx.tv.material3.Text(
            formatDuration(track.durationMs),
            color = TextSecondary,
            fontSize = AuraTypography.label,
            modifier = Modifier.padding(start = AuraSpacing.lg)
        )
        // Heart sits at the trailing edge, after the duration: filled accent =
        // liked, hollow grey = not.
        if (favorite != null) {
            Spacer(Modifier.width(AuraSpacing.xs))
            HeartToggle(
                favorite = favorite,
                accent = accent,
                interactive = false
            )
        }
        if (onRemove != null) {
            androidx.compose.material3.Icon(
                painter = androidx.compose.ui.res.painterResource(R.drawable.ic_close),
                contentDescription = "Remove",
                tint = TextSecondary,
                modifier = Modifier
                    .padding(start = AuraSpacing.md)
                    .size(AuraSizes.iconBadge)
                    .clickable { onRemove() }
            )
        }
    }
}

/**
 * Horizontal home-style shelf: title inset, cards bleed edge-to-edge with the
 * resting inset carried by spacers INSIDE the scroll row (Column padding would
 * clip the row and kill the bleed).
 */
@Composable
fun ItemShelf(
    title: String,
    items: List<JellyfinItem>?,
    serverUrl: String,
    focusKeeper: FocusKeeper,
    onFocused: (String) -> Unit,
    onOpen: (JellyfinItem, Int) -> Unit,
    size: Dp = AuraSizes.shelfCardWidth,
    showLoading: Boolean = true,
    badgeFor: ((JellyfinItem) -> String?)? = null,
    onLongPress: ((JellyfinItem) -> Unit)? = null,
    // True only while this row is on screen (Home passes the flag from
    // rememberOnScreen). Inside, the horizontal scroll offset narrows it again
    // to the covers actually in view: a 20-card row must not request 20 slots.
    animate: Boolean = false,
    // Attached to the scrolling row so Home can measure whether the shelf is
    // inside the window at all.
    modifier: Modifier = Modifier
) {
    // "Only Atmos" (Settings) filters shelves without a second request: artists
    // pass through, albums/tracks must be in the Atmos catalog.
    val shown = items?.let { list -> list.filter { com.hans.aura.data.AtmosFilter.keep(it) } }
    val scroll = rememberScrollState()
    when {
        items == null -> {
            if (showLoading) {
                androidx.tv.material3.Text(
                    "$title \u2026", color = TextSecondary, fontSize = AuraTypography.bodySmall,
                    modifier = Modifier.padding(horizontal = AuraSpacing.screenHorizontal)
                )
                Spacer(Modifier.height(AuraSpacing.md))
            }
        }
        shown.isNullOrEmpty() -> {}
        else -> {
            androidx.tv.material3.Text(
                title, style = MaterialTheme.typography.headlineSmall, color = TextPrimary,
                modifier = Modifier.padding(horizontal = AuraSpacing.screenHorizontal)
            )
            Spacer(Modifier.height(AuraSpacing.sm))

            val stepPx = with(LocalDensity.current) {
                size.toPx() + AuraSpacing.md.toPx()
            }
            var viewportPx by remember { mutableStateOf(0) }
            // derivedStateOf, not a plain read: scroll.value changes on every
            // scrolled pixel, but this only changes when a whole card passes.
            val animatableRange by remember(scroll, stepPx, animate) {
                derivedStateOf {
                    if (!animate || stepPx <= 0f || viewportPx <= 0) {
                        IntRange.EMPTY
                    } else {
                        val first = (scroll.value / stepPx).toInt()
                        // One screen of covers plus two: the lookahead that lets
                        // the next card start before it is fully scrolled in.
                        val count = (viewportPx / stepPx).toInt() + 2
                        first..(first + count)
                    }
                }
            }
            Row(
                modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { viewportPx = it.size.width }
                    .horizontalScroll(scroll)
                    .focusRestorer(),
                horizontalArrangement = Arrangement.spacedBy(AuraSpacing.md)
            ) {
                Spacer(Modifier.width(AuraSizes.shelfEdgeSpacer))
                shown.forEachIndexed { idx, item ->
                    ArtworkCard(
                        item = item,
                        serverUrl = serverUrl,
                        size = size,
                        badge = badgeFor?.invoke(item),
                        focusKeeper = focusKeeper,
                        onFocused = onFocused,
                        animate = item.type == "MusicAlbum" && idx in animatableRange,
                        // Earlier cards outrank later ones, so the budget keeps
                        // the left edge of the visible row moving.
                        motionPriority = -idx,
                        onLongPress = onLongPress?.let { cb -> { cb(item) } }
                    ) {
                        onOpen(item, idx)
                    }
                }
                Spacer(Modifier.width(AuraSizes.shelfEdgeSpacer))
            }
            Spacer(Modifier.height(AuraSpacing.md))
        }
    }
}

fun formatDuration(ms: Long): String {
    val total = ms / 1000
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}

/**
 * Human-readable "how stale is this" label for a wall-clock millisecond stamp.
 * Used by Settings for the last library refresh; 0 means "never".
 */
fun relativeTime(ms: Long): String {
    if (ms <= 0L) return "never"
    val elapsed = System.currentTimeMillis() - ms
    return when {
        elapsed < 60_000L -> "just now"
        elapsed < 3_600_000L -> "${elapsed / 60_000L} min ago"
        elapsed < 86_400_000L -> "${elapsed / 3_600_000L} h ago"
        else -> java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.US)
            .format(java.util.Date(ms))
    }
}
