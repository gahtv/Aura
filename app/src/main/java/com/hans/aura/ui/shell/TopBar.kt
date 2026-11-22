package com.hans.aura.ui.shell

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.hans.aura.AuraApp
import com.hans.aura.R
import com.hans.aura.data.AuthStore
import com.hans.aura.ui.common.AutoFitMarqueeText
import com.hans.aura.ui.nav.NavSpec
import com.hans.aura.ui.nav.Screen
import com.hans.aura.ui.player.PlayerConnection
import com.hans.aura.ui.theme.Accent
import com.hans.aura.ui.theme.AuraColors
import com.hans.aura.ui.theme.AuraShapes
import com.hans.aura.ui.theme.Surface
import com.hans.aura.ui.theme.SurfaceHigh
import com.hans.aura.ui.theme.TextPrimary
import com.hans.aura.ui.theme.TextSecondary

/** Top bar: tabs, then [now-playing pill] [search] [settings] at the right.
 *  Visible on every screen so navigation is always one press away. */
@Composable
internal fun TopBar(
    current: Screen,
    onSelect: (Screen) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenNowPlaying: () -> Unit,
    player: PlayerConnection
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 40.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NavSpec.tabs.forEach { tab ->
            val isLibTab = tab == Screen.Albums
            val selected = if (isLibTab) NavSpec.isLibraryScreen(current) else tab::class == current::class
            val label = if (isLibTab) "Library" else NavSpec.tabLabel(tab)
            TabButton(label = label, selected = selected) { onSelect(tab) }
            Spacer(Modifier.width(10.dp))
        }
        Spacer(Modifier.weight(1f))
        NowPlayingPill(player, current) { onOpenNowPlaying() }
        Spacer(Modifier.width(10.dp))
        IconNavButton(
            icon = painterResource(R.drawable.ic_nav_search),
            contentDescription = "Search",
            active = current is Screen.Search
        ) { onOpenSearch() }
        Spacer(Modifier.width(10.dp))
        IconNavButton(
            icon = painterResource(R.drawable.ic_nav_settings),
            contentDescription = "Settings",
            active = current is Screen.Settings
        ) { onOpenSettings() }
    }
}

/** Compact now-playing pill: art, title, artist, equalizer/paused state. Opens Now Playing. */
@Composable
private fun NowPlayingPill(player: PlayerConnection, current: Screen, onOpen: () -> Unit) {
    val item by player.current.collectAsState()
    val isPlaying by player.isPlaying.collectAsState()
    val app = LocalContext.current.applicationContext as AuraApp
    val serverUrl = app.jellyfin.auth.session?.serverUrl ?: return
    val playing = item ?: return

    var focused by remember { mutableStateOf(false) }
    val shape = AuraShapes.card
    Row(
        Modifier
            .clip(shape)
            .background(if (focused) SurfaceHigh else AuraColors.PillBackground)
            .then(
                if (focused) Modifier.border(3.dp, Accent, shape) else Modifier
            )
            // The pill is focusable (it is the top bar's primary entry point on
            // Now Playing); without this the flag never flips and it shows no
            // cursor ring even when a remote has it selected.
            .onFocusChanged { focused = it.isFocused }
            .clickable { onOpen() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        playing.primaryImageTag?.let { tag ->
            AsyncImage(
                model = AuthStore.imageUrl(serverUrl, playing.id, tag, maxWidth = 160),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(44.dp)
                    .height(44.dp)
                    .clip(AuraShapes.card)
                    .background(Surface)
            )
        }
        // Cap the pill width so a long title can't push Search/Settings
        // off the right edge of the top bar.
        Column(Modifier.padding(start = 12.dp).widthIn(max = 340.dp)) {
            // Shrink then marquee inside the cap, so a 60-character track name
            // can never widen the pill and push Search/Settings off screen.
            AutoFitMarqueeText(
                text = playing.name,
                // Upper bound only; the row hands out whatever is left, so the
                // pill can never push Search/Settings off the top bar.
                widthCap = 260.dp,
                sizes = listOf(14.sp, 13.sp, 12.sp),
                color = TextPrimary
            )
            androidx.tv.material3.Text(
                playing.artistNames.ifBlank { "" },
                color = TextSecondary, fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(10.dp))
        PlayingEqualizerIndicator(isPlaying = isPlaying, tint = Accent)
    }
}

@Composable
internal fun PlayingEqualizerIndicator(
    isPlaying: Boolean,
    tint: Color,
    modifier: Modifier = Modifier
) {
    if (!isPlaying) {
        androidx.compose.material3.Icon(
            painter = painterResource(R.drawable.ic_play_arrow),
            contentDescription = "Paused",
            tint = TextSecondary,
            modifier = modifier.size(16.dp)
        )
        return
    }
    val transition = rememberInfiniteTransition(label = "eqBars")
    val bar1 by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(420, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "eq1"
    )
    val bar2 by transition.animateFloat(
        initialValue = 0.95f,
        targetValue = 0.30f,
        animationSpec = infiniteRepeatable(tween(530, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "eq2"
    )
    val bar3 by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.90f,
        animationSpec = infiniteRepeatable(tween(370, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "eq3"
    )
    Row(
        modifier = modifier.height(14.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        listOf(bar1, bar2, bar3).forEach { level ->
            Box(
                Modifier
                    .width(3.dp)
                    .height(14.dp)
                    .graphicsLayer {
                        scaleY = level
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    }
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(tint)
            )
        }
    }
}

/** Square icon button for Search / Settings in the top bar. */
@Composable
private fun IconNavButton(
    icon: androidx.compose.ui.graphics.painter.Painter,
    contentDescription: String,
    active: Boolean,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = AuraShapes.control
    Box(
        Modifier
            .clip(shape)
            .background(
                when {
                    active -> AuraColors.ActiveTint
                    focused -> AuraColors.FocusedFill
                    else -> AuraColors.PillBackground
                }
            )
            .then(
                // Same grey cursor ring as the tabs, so the whole bar reads
                // consistently: grey = cursor, amber = current.
                if (focused) Modifier.border(2.dp, AuraColors.CursorRing, shape) else Modifier
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() }
            .padding(10.dp)
    ) {
        androidx.compose.material3.Icon(
            painter = icon,
            contentDescription = contentDescription,
            tint = if (active) Accent else TextPrimary,
            modifier = Modifier.width(22.dp).height(22.dp)
        )
    }
}

@Composable
private fun TabButton(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = AuraShapes.control
    // Loud, unambiguous states: selected = filled amber, focused = thick outline.
    // An unfocused-vs-focused unselected tab used to differ only by a warm-brown
    // amber ring on a warm-brown bar, which read as "nothing is focused" — the
    // cursor now gets a light GREY ring plus a lifted fill so it separates from
    // both the bar and the amber selection.
    val bg = when {
        selected && focused -> AuraColors.SelectedFocusedAccent
        selected -> Accent
        focused -> AuraColors.FocusedFill
        else -> Color.Transparent
    }
    Box(
        Modifier
            .clip(shape)
            .background(bg)
            .then(
                if (focused) Modifier.border(
                    2.dp, if (selected) TextPrimary else AuraColors.CursorRing, shape
                ) else Modifier
            )
            // Without this the `focused` flag never flips and NONE of the focus
            // styling below renders — the tabs showed only the amber selection.
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() }
            // Tighter horizontal padding: five tabs + pill + two icon buttons
            // have to coexist inside 960dp on a 1080p TV.
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        androidx.tv.material3.Text(
            label,
            color = if (selected) AuraColors.OnAccent else TextPrimary,
            fontSize = 17.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}
