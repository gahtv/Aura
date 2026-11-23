package com.hans.aura.playback

import android.content.Context
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import com.hans.aura.player.controller.PlaybackController
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
class PlaybackClockControllerTest {
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
