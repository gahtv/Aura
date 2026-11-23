@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.hans.aura.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.currentCompositeKeyHashCode
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import androidx.tv.material3.MaterialTheme
import com.hans.aura.R
import com.hans.aura.data.JellyfinItem
import com.hans.aura.ui.theme.AuraColors
import com.hans.aura.ui.theme.AuraMotion
import com.hans.aura.ui.theme.AuraShapes
import com.hans.aura.ui.theme.AuraSizes
import com.hans.aura.ui.theme.AuraSpacing
import com.hans.aura.ui.theme.AuraTypography
import com.hans.aura.ui.theme.Surface
import com.hans.aura.ui.theme.SurfaceHigh
import com.hans.aura.ui.theme.TextPrimary
import com.hans.aura.ui.theme.TextSecondary

private val CardFocusedShape = RoundedCornerShape(AuraShapes.cardRadius - AuraSizes.focusBorder)

/** D-pad-focusable artwork card, circular for artists and rounded-square for albums/playlists. */
@Composable
fun ArtworkCard(
    item: JellyfinItem,
    serverUrl: String,
    size: Dp,
    badge: String? = null,
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
    circular: Boolean = item.type == "MusicArtist",
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val artworkSource = LocalArtworkSource.current
    val artworkToken = currentCompositeKeyHashCode
    val sharedArtwork = if (!circular && artworkSource?.value == artworkToken) {
        Modifier.sharedArtwork(item.id, AuraShapes.cardRadius)
    } else Modifier.clip(if (circular) CircleShape else AuraShapes.card)
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) AuraMotion.artworkFocusedScale else 1f,
        animationSpec = AuraMotion.scheme.defaultSpatialSpec(),
        label = "artworkFocusScale"
    )
    Column(
        horizontalAlignment = if (circular) Alignment.CenterHorizontally else Alignment.Start,
        modifier = Modifier
            .width(size)
            .zIndex(if (focused) 2f else 0f)
            .then(
                if (focusKeeper != null) {
                    Modifier.focusRequester(focusKeeper.requesterFor(item.id))
                } else Modifier
            )
            .contextMenuKeys(onLongPress)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) {
                    artworkSource?.value = artworkToken
                    onFocused?.invoke(item.id)
                }
            }
            .clickable {
                artworkSource?.value = artworkToken
                onClick()
            }
            // Keep the spring inside the focus target so TV scrolling sees stable bounds.
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
    ) {
        val bg by animateColorAsState(
            if (focused) SurfaceHigh else Surface,
            animationSpec = AuraMotion.scheme.fastEffectsSpec(),
            label = "card"
        )
        val innerShape = if (circular) CircleShape else CardFocusedShape
        Box(
            modifier = Modifier
                .aspectRatio(1f)
                .then(sharedArtwork)
                .background(bg)
                .padding(if (focused) AuraSizes.focusBorder else 0.dp)
                .then(
                    // Unmistakable focus outline: accent ring on the artwork.
                    if (focused) Modifier.clip(innerShape).border(
                        width = AuraSizes.focusBorderLoud,
                        color = colors.primary,
                        shape = innerShape
                    ) else Modifier
                )
        ) {
            val artwork = item.resolveArtwork()
            if (artwork != null) {
                Box {
                    val still = artworkRequest(artwork.url(serverUrl, maxWidth = 480))
                    if (animate) {
                        // Same still image, with the motion loop over it when the
                        // budget grants a slot. Losing the slot changes nothing
                        // on screen: the poster is what was drawn before.
                        MotionArtwork(
                            itemId = artwork.itemId,
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
                                    colors.tertiaryContainer,
                                    AuraShapes.badge
                                )
                                .padding(horizontal = AuraSpacing.xxs, vertical = 2.dp)
                        ) {
                            androidx.tv.material3.Text(
                                label,
                                color = colors.onTertiaryContainer,
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
            textAlign = if (circular) TextAlign.Center else TextAlign.Start,
            modifier = Modifier.fillMaxWidth().padding(top = AuraSpacing.xs)
        )
        androidx.tv.material3.Text(
            subtitleOf(item),
            color = TextSecondary,
            fontSize = AuraTypography.caption,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (circular) TextAlign.Center else TextAlign.Start,
            modifier = Modifier.fillMaxWidth()
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
    focusKeeper: FocusKeeper? = null,
    onFocused: ((String) -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    // Liked state (null = draw no heart). Kept after every existing parameter
    // so no call site's trailing lambda can bind to it by accident.
    favorite: Boolean? = null,
    onToggleFavorite: (() -> Unit)? = null
) {
    val colors = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    val bg by animateColorAsState(
        when {
            isCurrent -> colors.secondaryContainer
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
                    .background(colors.primary)
            )
            Spacer(Modifier.width(AuraSpacing.md))
        }
        androidx.tv.material3.Text(
            text = if (isCurrent) "\u25B6" else index.toString(),
            color = if (isCurrent) colors.onSecondaryContainer else TextSecondary,
            fontSize = AuraTypography.bodySmall,
            modifier = Modifier.width(AuraSizes.trackIndexWidth)
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.tv.material3.Text(
                    track.name,
                    color = if (isCurrent) colors.onSecondaryContainer else TextPrimary,
                    fontSize = AuraTypography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (atmos) {
                    Spacer(Modifier.width(AuraSpacing.xs))
                    AtmosChip()
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
        // Focus-revealed inline action hints (strictly non-focusable so D-pad
        // focus stays on the row itself).
        AnimatedVisibility(
            visible = focused && (favorite != null || onToggleFavorite != null || onLongPress != null),
            enter = fadeIn(AuraMotion.scheme.fastEffectsSpec()),
            exit = fadeOut(AuraMotion.scheme.fastEffectsSpec())
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AuraSpacing.xs),
                modifier = Modifier.padding(start = AuraSpacing.sm)
            ) {
                if (favorite != null || onToggleFavorite != null) {
                    androidx.tv.material3.Text(
                        text = "\u2665",
                        color = if (favorite == true) colors.primary else TextSecondary,
                        fontSize = AuraTypography.label
                    )
                }
                if (onLongPress != null) {
                    androidx.tv.material3.Text(
                        text = "\u2022\u2022\u2022",
                        color = TextSecondary,
                        fontSize = AuraTypography.label,
                        modifier = Modifier
                            .clip(AuraShapes.badge)
                            .background(AuraColors.FocusedFillSubtle)
                            .padding(horizontal = AuraSpacing.xs, vertical = 2.dp)
                    )
                }
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
    // True while this row is in the lazy list's motion window. Inside, the
    // horizontal scroll offset narrows it to the covers actually in view:
    // a 20-card row must not request 20 slots.
    animate: Boolean = false,
    // Applied to the scrolling row.
    modifier: Modifier = Modifier
) {
    // "Only Atmos" (Settings) filters shelves without a second request: artists
    // pass through, albums/tracks must be in the Atmos catalog.
    val shown = items?.let { list -> list.filter { com.hans.aura.data.AtmosFilter.keep(it) } }
    val listState = rememberLazyListState()
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

            // derivedStateOf: only updates when visible item indices change.
            val animatableRange by remember(listState, animate) {
                derivedStateOf {
                    if (!animate) {
                        IntRange.EMPTY
                    } else {
                        val visible = listState.layoutInfo.visibleItemsInfo
                        if (visible.isEmpty()) IntRange.EMPTY
                        else visible.first().index..(visible.last().index + 2)
                    }
                }
            }
            LazyRow(
                state = listState,
                modifier = modifier
                    .fillMaxWidth()
                    .focusRestorer(),
                contentPadding = PaddingValues(
                    horizontal = AuraSizes.shelfEdgeSpacer,
                    vertical = AuraMotion.artworkFocusInset(size)
                ),
                horizontalArrangement = Arrangement.spacedBy(AuraSpacing.md)
            ) {
                itemsIndexed(
                    items = shown,
                    key = { _, item -> item.id },
                    contentType = { _, item -> item.type }
                ) { idx, item ->
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
