package com.hans.aura.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.hans.aura.R
import com.hans.aura.ui.common.resolveArtwork
import com.hans.aura.ui.nav.NavSpec
import com.hans.aura.ui.nav.Screen
import com.hans.aura.ui.player.PlayerConnection
import com.hans.aura.ui.theme.AuraColors
import com.hans.aura.ui.theme.AuraMotion
import com.hans.aura.ui.theme.AuraShapes
import com.hans.aura.ui.theme.AuraSizes
import com.hans.aura.ui.theme.AuraSpacing
import com.hans.aura.ui.theme.Surface
import com.hans.aura.ui.theme.TextPrimary
import com.hans.aura.ui.theme.TextSecondary

internal enum class RailDestination(val screen: Screen, val label: String, val icon: Int) {
    Home(Screen.Home, "Home", R.drawable.ic_nav_home),
    Library(Screen.Albums, "Library", R.drawable.ic_library_music),
    Genres(Screen.Genres, "Genres", R.drawable.ic_category),
    Liked(Screen.Liked, "Liked", R.drawable.ic_favorite),
    Search(Screen.Search, "Search", R.drawable.ic_nav_search),
    Settings(Screen.Settings, "Settings", R.drawable.ic_nav_settings),
    NowPlaying(Screen.NowPlaying, "Now Playing", R.drawable.ic_play_arrow);

    companion object {
        val mainEntries = entries.filterNot { it == NowPlaying }

        fun forStack(stack: List<Screen>): RailDestination = stack.asReversed()
            .firstNotNullOfOrNull { screen ->
                when {
                    NavSpec.isLibraryScreen(screen) -> Library
                    screen is Screen.Genre -> Genres
                    else -> entries.firstOrNull { it.screen == screen }
                }
            } ?: Home
    }
}

internal data class RailPlayback(
    val title: String,
    val artist: String,
    val artworkUrl: String?,
    val isPlaying: Boolean
)

@Composable
internal fun rememberRailPlayback(player: PlayerConnection, serverUrl: String?): RailPlayback? {
    val item by player.current.collectAsState()
    val isPlaying by player.isPlaying.collectAsState()
    return remember(item, isPlaying, serverUrl) {
        item?.let { track ->
            RailPlayback(
                title = track.name,
                artist = track.artistNames,
                artworkUrl = serverUrl?.let { track.resolveArtwork()?.url(it, maxWidth = 96) },
                isPlaying = isPlaying
            )
        }
    }
}

internal val collapsedRailWidth = AuraSizes.minTarget + AuraSpacing.sm * 2
private val expandedWidth = 224.dp
private val entryHeight = AuraSizes.minTarget

@Composable
internal fun NavigationRailLayout(
    showRail: Boolean,
    selected: RailDestination,
    playback: RailPlayback?,
    onSelect: (RailDestination) -> Unit,
    insetContent: Boolean = true,
    content: @Composable () -> Unit
) {
    val contentFocus = remember { FocusRequester() }
    Box(Modifier.fillMaxSize()) {
        // Reserve only the icon column. Expansion overlays the page so it
        // doesn't remeasure the lists or move the focused content under the cursor.
        Box(
            Modifier
                .fillMaxSize()
                .padding(start = if (showRail && insetContent) collapsedRailWidth else 0.dp)
                .focusRequester(contentFocus)
                .focusRestorer()
                .focusGroup()
        ) { content() }

        if (showRail) {
            NavigationRail(
                selected = selected,
                playback = playback,
                onSelect = onSelect,
                onReturnToContent = { contentFocus.requestFocus() }
            )
        }
    }
}

@Composable
private fun NavigationRail(
    selected: RailDestination,
    playback: RailPlayback?,
    onSelect: (RailDestination) -> Unit,
    onReturnToContent: () -> Boolean
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val expansion by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = AuraMotion.scheme.defaultSpatialSpec(),
        label = "Navigation rail"
    )
    val progress = expansion.coerceIn(0f, 1f)
    val width = lerp(collapsedRailWidth, expandedWidth, progress)
    val labelAlpha = ((progress - 0.35f) / 0.65f).coerceIn(0f, 1f)

    BackHandler(enabled = expanded) {
        if (!onReturnToContent()) focusManager.clearFocus()
    }
    if (progress > 0f) {
        Box(Modifier.fillMaxSize().background(AuraColors.NeutralScrim.copy(alpha = 0.55f * progress)))
    }
    Column(
        Modifier
            .width(width)
            .fillMaxHeight()
            .background(Surface)
            .onFocusChanged { expanded = it.hasFocus }
            .focusProperties {
                onEnter = {
                    if (requestedFocusDirection == FocusDirection.Left) selectedFocus.requestFocus()
                }
                onExit = {
                    if (requestedFocusDirection == FocusDirection.Up ||
                        requestedFocusDirection == FocusDirection.Down
                    ) cancelFocusChange()
                }
            }
            .focusGroup()
            .onPreviewKeyEvent {
                it.type == KeyEventType.KeyDown && it.key == Key.DirectionRight && onReturnToContent()
            }
            .padding(horizontal = AuraSpacing.sm, vertical = AuraSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(AuraSpacing.sm)
    ) {
        Row(
            Modifier.height(entryHeight).clearAndSetSemantics {},
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.width(entryHeight), contentAlignment = Alignment.Center) {
                Icon(
                    painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(32.dp).clip(AuraShapes.control)
                )
            }
            if (labelAlpha > 0f) {
                Text(
                    "Aura",
                    modifier = Modifier.graphicsLayer { alpha = labelAlpha },
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                    maxLines = 1
                )
            }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(AuraSpacing.xxs)
        ) {
            RailDestination.mainEntries.forEach { destination ->
                RailItem(
                    destination = destination,
                    selected = selected == destination,
                    labelAlpha = labelAlpha,
                    modifier = if (selected == destination) Modifier.focusRequester(selectedFocus) else Modifier,
                    onClick = { onSelect(destination) }
                )
            }
        }
        Box(
            Modifier.fillMaxWidth().height(AuraSizes.hairline)
                .background(TextSecondary.copy(alpha = 0.18f))
        )
        RailItem(
            destination = RailDestination.NowPlaying,
            selected = selected == RailDestination.NowPlaying,
            labelAlpha = labelAlpha,
            modifier = if (selected == RailDestination.NowPlaying) Modifier.focusRequester(selectedFocus) else Modifier,
            playback = playback,
            onClick = { onSelect(RailDestination.NowPlaying) }
        )
    }
}

@Composable
private fun RailItem(
    destination: RailDestination,
    selected: Boolean,
    labelAlpha: Float,
    modifier: Modifier = Modifier,
    playback: RailPlayback? = null,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    val shape = AuraShapes.control
    val fill = when {
        selected -> colors.primary
        focused -> AuraColors.FocusedFill
        else -> Color.Transparent
    }
    val ink = if (selected) colors.onPrimary else colors.onSurface
    val outline = if (focused) Modifier.border(AuraSizes.focusBorder, AuraColors.CursorRing, shape)
    else Modifier
    val description = if (playback == null) destination.label else buildString {
        append("${destination.label}, ${playback.title}")
        if (playback.artist.isNotBlank()) append(", ${playback.artist}")
        append(if (playback.isPlaying) ", playing" else ", paused")
    }
    Row(
        modifier
            .fillMaxWidth()
            .height(entryHeight)
            .clip(shape)
            .background(fill)
            .then(outline)
            .onFocusChanged { focused = it.isFocused }
            .semantics { contentDescription = description }
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.width(entryHeight).clearAndSetSemantics {},
            contentAlignment = Alignment.Center
        ) {
            if (playback == null) {
                Icon(painterResource(destination.icon), null, tint = ink, modifier = Modifier.size(24.dp))
            } else if (playback.artworkUrl == null) {
                PlayingEqualizerIndicator(playback.isPlaying, tint = ink)
            } else {
                Box(Modifier.size(36.dp)) {
                    AsyncImage(
                        model = playback.artworkUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(AuraShapes.control)
                    )
                    Box(
                        Modifier.align(Alignment.BottomEnd).size(18.dp)
                            .clip(AuraShapes.badge).background(Surface),
                        contentAlignment = Alignment.Center
                    ) {
                        PlayingEqualizerIndicator(
                            playback.isPlaying,
                            tint = colors.primary,
                            modifier = Modifier.graphicsLayer {
                                scaleX = 0.65f
                                scaleY = 0.65f
                            }
                        )
                    }
                }
            }
        }
        if (labelAlpha > 0f) {
            Column(
                Modifier.padding(end = AuraSpacing.sm)
                    .graphicsLayer { alpha = labelAlpha }
                    .clearAndSetSemantics {}
            ) {
                Text(
                    destination.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (playback != null) {
                    Text(
                        if (playback.artist.isBlank()) playback.title else "${playback.title} · ${playback.artist}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (selected) ink else TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
