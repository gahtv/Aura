package com.hans.aura.playback

import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.test.core.app.ApplicationProvider
import com.hans.aura.player.controller.PlaybackController
import com.hans.aura.player.controller.RepeatPlaybackAnchor
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class PlaybackClockControllerTest {
    @Test
    fun identicalRepeatSnapshotsReanchorEveryTime() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = ExoPlayer.Builder(context).build()
        val session = MediaSession.Builder(context, engine).build()
        val playback = PlaybackController(context)
        val future = MediaController.Builder(context, session.token)
            .setListener(ReflectionHelpers.getField(playback, "sessionListener"))
            .buildAsync()
        shadowOf(Looper.getMainLooper()).idle()
        val controller = future.get()
        val repeatingPlayer = object : ForwardingPlayer(engine) {
            override fun getCurrentPosition() = 0L
            override fun getDuration() = 1000L
            override fun isPlaying() = true
            override fun getPlaybackParameters() = PlaybackParameters(1.5f)
        }
        try {
            repeat(3) {
                val uptime = SystemClock.uptimeMillis()
                session.broadcastCustomCommand(
                    RepeatPlaybackAnchor.command, RepeatPlaybackAnchor.snapshot(repeatingPlayer)
                )
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(uptime, playback.clock.value.anchorUptimeMs)
                assertEquals(0L, playback.clock.value.positionMs)
                assertEquals(150L, playback.clock.value.positionAt(uptime + 100L))
                assertEquals(1000L, playback.durationMs.value)
                ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
                assertEquals(1000L, playback.clock.value.positionAt(SystemClock.uptimeMillis()))
            }
            val anchor = playback.clock.value
            session.broadcastCustomCommand(RepeatPlaybackAnchor.command, Bundle.EMPTY)
            session.broadcastCustomCommand(SessionCommand("unrelated", Bundle.EMPTY), Bundle.EMPTY)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(anchor, playback.clock.value)
        } finally {
            controller.release()
            playback.release()
            session.release()
            engine.release()
        }
    }

    @Test
    fun lateResumeReanchorsAndReleaseResets() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = ExoPlayer.Builder(context).build()
        engine.setMediaItem(MediaItem.fromUri("https://example.test/music.mp3"))
        val session = MediaSession.Builder(context, engine).build()
        val future = MediaController.Builder(context, session.token).buildAsync()
        shadowOf(Looper.getMainLooper()).idle()
        val controller = future.get()
        val playback = PlaybackController(context)
        ReflectionHelpers.getField<MutableStateFlow<MediaController?>>(playback, "_controller").value = controller
        ReflectionHelpers.callInstanceMethod<Void>(
            playback, "attach", ReflectionHelpers.ClassParameter.from(MediaController::class.java, controller)
        )
        try {
            assertFalse(playback.clock.value.playing)
            playback.publishPosition(8000)
            assertEquals(8000L, playback.clock.value.positionAt(Long.MAX_VALUE))
            playback.release()
            assertEquals(0L, playback.clock.value.positionAt(Long.MAX_VALUE))
        } finally {
            playback.release()
            session.release()
            engine.release()
        }
    }
}
