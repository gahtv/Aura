@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.hans.aura.ui.nowplaying

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.graphics.shapes.Morph
import androidx.media3.common.Player
import com.hans.aura.R
import com.hans.aura.ui.common.formatDuration
import com.hans.aura.ui.player.PlayerConnection
import com.hans.aura.ui.player.nextRepeatMode
import com.hans.aura.ui.theme.AuraColors
import com.hans.aura.ui.theme.AuraMotion
import com.hans.aura.ui.theme.AuraSizes
import com.hans.aura.ui.theme.AuraSpacing
import com.hans.aura.ui.theme.AuraTypography
import com.hans.aura.ui.theme.TextPrimary
import com.hans.aura.ui.theme.TextSecondary

/**
 * Icon-only transport controls under the seek bar, using the dynamic accent.
 * Layout: seek bar, times, transport row (prev / -10s / play-pause / +10s /
 * next), then shuffle + repeat + heart + Radio toggles.
 *
 * [onRadio] seeds an Instant Mix from the current track (null when nothing is
 * playing, so the control simply is not drawn).
 */
@Composable
internal fun LiveTransportBar(
    player: PlayerConnection,
    isPlaying: Boolean,
    playPauseFocus: FocusRequester,
    accent: Color,
    onOpenPeek: (FocusRequester) -> Boolean,
    favorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    onRadio: (() -> Unit)? = null,
    dimMode: Boolean = false,
    onToggleDimMode: (() -> Unit)? = null
) {
    TransportBarContent(
        player = player,
        isPlaying = isPlaying,
        playPauseFocus = playPauseFocus,
        accent = accent,
        onOpenPeek = onOpenPeek,
        favorite = favorite,
        onToggleFavorite = onToggleFavorite,
        onRadio = onRadio,
        dimMode = dimMode,
        onToggleDimMode = onToggleDimMode,
        seekSection = {
            val position by player.positionMs.collectAsState()
            val duration by player.durationMs.collectAsState()
            SeekSection(position, duration, isPlaying, accent, playPauseFocus, player::seekBy)
        }
    )
}

@Composable
internal fun TransportBar(
    player: PlayerConnection,
    position: Long,
    duration: Long,
    isPlaying: Boolean,
    playPauseFocus: FocusRequester,
    accent: Color,
    onOpenPeek: (FocusRequester) -> Boolean,
    favorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    onRadio: (() -> Unit)? = null,
    dimMode: Boolean = false,
    onToggleDimMode: (() -> Unit)? = null
) {
    TransportBarContent(
        player = player,
        isPlaying = isPlaying,
        playPauseFocus = playPauseFocus,
        accent = accent,
        onOpenPeek = onOpenPeek,
        favorite = favorite,
        onToggleFavorite = onToggleFavorite,
        onRadio = onRadio,
        dimMode = dimMode,
        onToggleDimMode = onToggleDimMode,
        seekSection = {
            SeekSection(position, duration, isPlaying, accent, playPauseFocus, player::seekBy)
        }
    )
}

@Composable
private fun SeekSection(
    position: Long,
    duration: Long,
    isPlaying: Boolean,
    accent: Color,
    playPauseFocus: FocusRequester,
    onSeek: (Long) -> Unit
) {
    WavySeekBar(
        progress = if (duration > 0) position.toFloat() / duration else 0f,
        isPlaying = isPlaying,
        accent = accent,
        modifier = Modifier.focusProperties { down = playPauseFocus },
        onSeek = onSeek
    )
    Spacer(Modifier.height(AuraSpacing.xs))
    Row(Modifier.fillMaxWidth()) {
        androidx.tv.material3.Text(
            formatDuration(position),
            color = TextSecondary,
            fontSize = AuraTypography.caption
        )
        Spacer(Modifier.weight(1f))
        androidx.tv.material3.Text(
            "-${formatDuration((duration - position).coerceAtLeast(0))}",
            color = TextSecondary, fontSize = AuraTypography.caption
        )
    }
}

@Composable
private fun TransportBarContent(
    player: PlayerConnection,
    isPlaying: Boolean,
    playPauseFocus: FocusRequester,
    accent: Color,
    onOpenPeek: (FocusRequester) -> Boolean,
    favorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    onRadio: (() -> Unit)? = null,
    dimMode: Boolean = false,
    onToggleDimMode: (() -> Unit)? = null,
    seekSection: @Composable () -> Unit
) {
    val shuffleEnabled by player.shuffleEnabled.collectAsState()
    val repeatMode by player.repeatMode.collectAsState()
    val shuffleFocus = remember { FocusRequester() }
    val repeatFocus = remember { FocusRequester() }

    fun peekOnDown(origin: FocusRequester) = Modifier
        .focusRequester(origin)
        .onPreviewKeyEvent { event ->
            event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown &&
                event.nativeKeyEvent.repeatCount == 0 && onOpenPeek(origin)
        }

    Column {
        seekSection()
        Spacer(Modifier.height(AuraSpacing.lg))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TransportIconButton(R.drawable.ic_skip_previous, "Previous", accent = accent) {
                player.previous()
            }
            Spacer(Modifier.width(AuraSpacing.md))
            TransportIconButton(R.drawable.ic_replay_10, "Back 10 seconds", accent = accent) {
                player.seekTo((player.positionMs.value - 10_000).coerceAtLeast(0))
            }
            Spacer(Modifier.width(AuraSpacing.md))
            // Big emphasized play/pause.
            TransportIconButton(
                icon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow,
                label = if (isPlaying) "Pause" else "Play",
                accent = accent,
                iconSize = AuraSizes.transportIconLarge,
                filled = true,
                squircle = isPlaying,
                modifier = Modifier
                    .focusRequester(playPauseFocus)
                    .focusProperties { down = shuffleFocus }
            ) {
                player.playPause()
            }
            Spacer(Modifier.width(AuraSpacing.md))
            TransportIconButton(R.drawable.ic_forward_10, "Forward 10 seconds", accent = accent) {
                val dur = player.durationMs.value.coerceAtLeast(1)
                player.seekTo((player.positionMs.value + 10_000).coerceIn(0, dur))
            }
            Spacer(Modifier.width(AuraSpacing.md))
            TransportIconButton(R.drawable.ic_skip_next, "Next", accent = accent) {
                player.next()
            }
        }
        Spacer(Modifier.height(AuraSpacing.md))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TransportIconButton(
                icon = R.drawable.ic_shuffle,
                label = "Shuffle",
                accent = accent,
                active = shuffleEnabled,
                modifier = peekOnDown(shuffleFocus)
            ) {
                player.setShuffle(!shuffleEnabled)
            }
            Spacer(Modifier.width(AuraSpacing.lg))
            TransportIconButton(
                icon = if (repeatMode == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat,
                label = when (repeatMode) {
                    Player.REPEAT_MODE_ONE -> "Repeat one"
                    Player.REPEAT_MODE_ALL -> "Repeat all"
                    else -> "Repeat off"
                },
                accent = accent,
                active = repeatMode != Player.REPEAT_MODE_OFF,
                modifier = peekOnDown(repeatFocus)
            ) {
                player.setRepeat(nextRepeatMode(repeatMode))
            }
            if (onToggleFavorite != null) {
                Spacer(Modifier.width(AuraSpacing.lg))
                // Heart for the CURRENT track: filled accent when liked.
                TransportHeartButton(
                    favorite = favorite,
                    accent = accent,
                    onClick = onToggleFavorite
                )
            }
            onRadio?.let { radio ->
                Spacer(Modifier.width(AuraSpacing.lg))
                // Track radio: same loud focus language as the top-row control
                // buttons, sat in the transport row next to the heart. No
                // `active` state — pressing it starts a new mix, it is not a
                // toggle.
                NpControlButton(label = "Radio", onClick = radio)
            }
            onToggleDimMode?.let { toggleDim ->
                Spacer(Modifier.width(AuraSpacing.lg))
                TransportIconButton(
                    icon = R.drawable.ic_bedtime,
                    label = "Dim mode",
                    accent = accent,
                    active = dimMode
                ) {
                    toggleDim()
                }
            }
        }
    }
}

/**
 * Circular icon transport button: primary controls use a solid accent plate;
 * secondary controls use a tinted plate when focused or active.
 */
@Composable
private fun TransportIconButton(
    icon: Int,
    label: String,
    accent: Color,
    iconSize: Dp = AuraSizes.transportIcon,
    filled: Boolean = false,
    active: Boolean = false,
    squircle: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val plate by animateColorAsState(
        when {
            filled -> accent
            focused -> accent.copy(alpha = 0.34f)
            active -> accent.copy(alpha = 0.18f)
            else -> Color.Transparent
        },
        animationSpec = AuraMotion.scheme.fastEffectsSpec(),
        label = "transportPlate"
    )
    val tint by animateColorAsState(
        when {
            filled -> AuraColors.OnAccent
            active || focused -> accent
            else -> TextPrimary
        },
        animationSpec = AuraMotion.scheme.fastEffectsSpec(),
        label = "transportTint"
    )
    // Focus grows the glyph (~15%) as well as ringing it: on a dark 10-foot
    // screen a 3dp accent ring alone was too easy to miss.
    val grow by animateFloatAsState(
        if (focused) 1.15f else 1f,
        animationSpec = AuraMotion.scheme.fastSpatialSpec(),
        label = "transportGrow"
    )
    val buttonShape = if (filled) playPauseShape(squircle) else CircleShape
    Box(
        modifier
            .size(iconSize + AuraSizes.transportPlateExtra)
            .graphicsLayer {
                scaleX = grow
                scaleY = grow
            }
            .clip(buttonShape)
            .background(plate)
            .then(
                if (focused) Modifier.border(AuraSizes.focusBorderLoud, AuraColors.CursorRing, buttonShape)
                else Modifier
            )
            // onFocusChanged must sit BEFORE the focus target (clickable):
            // placed after it, it observes the un-focusable layout child and
            // never reports focus, so the ring/scale never rendered.
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClickLabel = label) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.material3.Icon(
            painter = painterResource(icon),
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(iconSize)
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val playPauseMorph by lazy { Morph(MaterialShapes.Circle, MaterialShapes.Square) }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun playPauseShape(playing: Boolean): Shape {
    val progress by animateFloatAsState(
        if (playing) 1f else 0f,
        animationSpec = AuraMotion.scheme.defaultSpatialSpec(),
        label = "playPauseShape"
    )
    val fraction = progress.coerceIn(0f, 1f)
    return remember(fraction) {
        GenericShape { size, _ ->
            playPauseMorph.toPath(fraction, path = this)
            transform(Matrix().apply { scale(size.width, size.height) })
        }
    }
}

/**
 * Heart variant of the transport button. Same plate/ring/scale language as
 * TransportIconButton, but the glyph is a text heart because there is no heart
 * vector drawable in res/. `active` (liked) keeps the accent tint at rest.
 */
@Composable
private fun TransportHeartButton(favorite: Boolean, accent: Color, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val plate by animateColorAsState(
        when {
            focused -> accent.copy(alpha = 0.34f)
            favorite -> accent.copy(alpha = 0.18f)
            else -> Color.Transparent
        },
        animationSpec = AuraMotion.scheme.fastEffectsSpec(),
        label = "heartPlate"
    )
    val tint by animateColorAsState(
        if (favorite || focused) accent else TextPrimary,
        animationSpec = AuraMotion.scheme.fastEffectsSpec(),
        label = "heartTint"
    )
    val grow by animateFloatAsState(
        if (focused) 1.15f else 1f,
        animationSpec = AuraMotion.scheme.fastSpatialSpec(),
        label = "heartGrow"
    )
    val label = if (favorite) "Remove from favorites" else "Add to favorites"
    Box(
        Modifier
            .size(AuraSizes.transportButtonPlate)
            .graphicsLayer {
                scaleX = grow
                scaleY = grow
            }
            .clip(CircleShape)
            .background(plate)
            .border(
                width = AuraSizes.focusBorderLoud,
                color = if (focused) AuraColors.CursorRing else Color.Transparent,
                shape = CircleShape
            )
            // Before the focus target, as everywhere else in the app.
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClickLabel = label) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.material3.Icon(
            painter = painterResource(
                if (favorite) R.drawable.ic_favorite_fill1
                else R.drawable.ic_favorite
            ),
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(AuraSizes.transportIcon)
        )
    }
}
