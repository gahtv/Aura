package com.hans.aura.ui.common

import android.content.Context
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
import com.hans.aura.data.MusicSources

/**
 * Cover art for an album or a playing track, with the backend's motion loop
 * drawn over it when there is one.
 *
 * The static [posterModel] is always drawn first and stays as the poster: the
 * loop fades in only once it has actually rendered a frame, and anything that
 * prevents that (no capability, no URL, a 404 from the index, a decoder error)
 * leaves the still cover exactly as it was — never an error state.
 *
 * The loop is muted (the audio track is the only sound) and pauses whenever the
 * audio player is paused or the screen is not in the foreground, so it never
 * spins a second decoder for nothing on TV hardware.
 *
 * [budgeted] opts a call site into the process-wide [MotionBudget]: used by
 * browse cards, where dozens of covers are on screen at once. Such a call asks
 * for a slot and animates only if it wins one; losing is invisible, because the
 * still poster is already drawn underneath. Detail and Now Playing leave it
 * false — they are one player each on a screen of their own and always animate.
 */
@Composable
fun MotionArtwork(
    itemId: String?,
    posterModel: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    playing: Boolean = true,
    budgeted: Boolean = false,
    // Higher keeps its slot when the budget is full; browse cards pass a rank
    // so the on-screen top rows beat the prefetch rows below them.
    motionPriority: Int = 0
) {
    Box(modifier) {
        if (posterModel != null) {
            // matchParentSize, not fillMaxSize: a fillMaxSize child inside a Box measured to
            // its content settles at its own intrinsic size, which drew the cover as a small
            // inset in the corner of the tile.
            AsyncImage(
                model = posterModel,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
        }
        // Capability gate: a source that does not serve motion loops (Jellyfin)
        // composes nothing extra at all.
        val source = MusicSources.current
        val url = remember(source, itemId) {
            if (source?.servesMotionArtwork != true || itemId.isNullOrBlank()) {
                null
            } else {
                runCatching { source.motionArtworkUrl(itemId) }.getOrNull()
            }
        }
        if (url != null) {
            if (budgeted && !itemId.isNullOrBlank()) {
                BudgetedMotionLoop(
                    itemId = itemId,
                    url = url,
                    playing = playing,
                    priority = motionPriority,
                    modifier = Modifier.matchParentSize()
                )
            } else {
                MotionLoop(
                    url = url,
                    // The url only exists for a non-blank id; the cast is for the compiler.
                    itemId = itemId.orEmpty(),
                    playing = playing,
                    modifier = Modifier.matchParentSize()
                )
            }
        }
    }
}

/**
 * A loop that only exists while the budget grants this card a slot.
 *
 * Acquire and release are keyed on the same trio so a priority change (the card
 * became visible, or fell back into the prefetch rows) is a clean hand-off:
 * the old slot is released before the new request, and the composable never
 * leaks a reference. When the budget refuses — or evicts this card later — the
 * loop is simply not composed and the poster above it is all that shows.
 */
@Composable
private fun BudgetedMotionLoop(
    itemId: String,
    url: String,
    playing: Boolean,
    priority: Int,
    modifier: Modifier = Modifier
) {
    val active by MotionBudget.active.collectAsState()
    var granted by remember(itemId) { mutableStateOf(false) }

    LaunchedEffect(itemId, url, priority) {
        granted = MotionBudget.acquire(itemId, priority)
    }
    DisposableEffect(itemId, url, priority) {
        // Only the holder releases: a card that was refused (or a duplicate of
        // an already-animating id) must not free someone else's slot.
        onDispose { if (granted) MotionBudget.release(itemId) }
    }

    if (granted && itemId in active) {
        MotionLoop(url = url, itemId = itemId, playing = playing, modifier = modifier)
    }
}

/** The looping surface itself: one ExoPlayer, muted, REPEAT_ONE, cropped to fill. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun MotionLoop(
    url: String,
    itemId: String,
    playing: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var failed by remember(url) { mutableStateOf(false) }
    var firstFrame by remember(url) { mutableStateOf(false) }
    // Foreground flag: an activity that goes to the background must stop the loop
    // even though it stays in composition.
    var foreground by remember(url) { mutableStateOf(true) }
    var videoWidth by remember(url) { mutableIntStateOf(0) }
    var videoHeight by remember(url) { mutableIntStateOf(0) }

    val player = remember(url) { buildLoopPlayer(context, url) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                firstFrame = true
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                videoWidth = videoSize.width
                videoHeight = videoSize.height
            }

            override fun onPlayerError(error: PlaybackException) {
                // A 404 (album has no loop) lands here: drop the surface and let
                // the static cover stand in, with no error UI.
                // Log the album id, never the URL: its query string carries the
                // index token and logcat is readable by anything on the device.
                android.util.Log.i(
                    "AURA_MOTION",
                    "motion unavailable album=$itemId code=${error.errorCodeName}"
                )
                failed = true
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            // Leaving composition (screen popped) is also what stops the loop.
            player.release()
        }
    }

    DisposableEffect(player, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    foreground = false
                    player.pause()
                }
                Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME -> foreground = true
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Audio paused -> loop paused; audio resumed -> loop resumes.
    LaunchedEffect(player, playing, foreground, failed) {
        if (playing && foreground && !failed) player.play() else player.pause()
    }

    // A loop that failed (404) has nothing left to do: stop decoding instead of
    // leaving a prepared surface behind the still cover.
    LaunchedEffect(failed) { if (failed) player.stop() }

    if (failed) return

    val alpha by animateFloatAsState(
        targetValue = if (firstFrame) 1f else 0f,
        label = "motionArtwork"
    )
    val vw = videoWidth
    val vh = videoHeight

    // media3's own view does the scaling: RESIZE_MODE_ZOOM is exactly "fill and crop", and it
    // re-applies on every layout. Hand-rolled TextureView matrices leave the frame drawn at the
    // wrong size for a few frames (a small inset in the corner of the tile).
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                this.player = player
            }
        },
        onRelease = { view -> view.player = null },
        modifier = modifier.graphicsLayer { this.alpha = alpha }
    )
}

/** Muted, silent, looping: motion artwork is texture, never a second soundtrack. */
private fun buildLoopPlayer(context: Context, url: String): ExoPlayer =
    ExoPlayer.Builder(context.applicationContext).build().apply {
        setMediaItem(MediaItem.fromUri(url))
        repeatMode = Player.REPEAT_MODE_ONE
        volume = 0f
        playWhenReady = false
        prepare()
    }
