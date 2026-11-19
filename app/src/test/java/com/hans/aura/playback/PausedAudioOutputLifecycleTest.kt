package com.hans.aura.playback

import android.os.Looper
import androidx.media3.common.FlagSet
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.lang.reflect.Proxy
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class PausedAudioOutputLifecycleTest {
    private var ready = false
    private var state = Player.STATE_READY
    private var mime = "audio/eac3-joc"
    private var stops = 0
    private var items = 1
    private val player = Proxy.newProxyInstance(
        ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)
    ) { _, method, _ ->
        when (method.name) {
            "getApplicationLooper" -> Looper.getMainLooper()
            "getPlayWhenReady" -> ready
            "getPlaybackState" -> state
            "getMediaItemCount" -> items
            "getAudioFormat" -> Format.Builder().setSampleMimeType(mime).build()
            "stop" -> { stops++; state = Player.STATE_IDLE; null }
            "removeListener" -> null
            else -> error("Unexpected call: ${method.name}")
        }
    } as ExoPlayer

    private fun update(output: PausedAudioOutput) {
        output.onEvents(player, Player.Events(FlagSet.Builder().build()))
    }

    private fun advance(seconds: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))
    }

    @Test
    fun `encoded pause releases only after grace period and retains released state in idle`() {
        val output = PausedAudioOutput(player)
        update(output)
        advance(2)
        assertEquals(0, stops)
        advance(1)
        assertEquals(1, stops)
        update(output)
        assertTrue(output.released)
        ready = true
        update(output)
        assertFalse(output.released)
        output.close()
    }

    @Test
    fun `skipping tracks while released keeps paused state`() {
        val output = PausedAudioOutput(player)
        update(output)
        advance(3)
        assertTrue(output.released)
        output.onEvents(
            player,
            Player.Events(FlagSet.Builder().add(Player.EVENT_MEDIA_ITEM_TRANSITION).build())
        )
        assertTrue(output.released)
        output.close()
    }

    @Test
    fun `clearing the queue after release ends internal release`() {
        val output = PausedAudioOutput(player)
        update(output)
        advance(3)
        assertTrue(output.released)
        items = 0
        update(output)
        assertFalse(output.released)
        output.close()
    }

    @Test
    fun `quick resume cancels release`() {
        val output = PausedAudioOutput(player)
        update(output)
        advance(2)
        ready = true
        update(output)
        advance(4)
        assertEquals(0, stops)
        assertFalse(output.released)
        output.close()
    }

    @Test
    fun `FLAC pause never releases`() {
        mime = "audio/flac"
        val output = PausedAudioOutput(player)
        update(output)
        advance(10)
        assertEquals(0, stops)
        output.close()
    }

    @Test
    fun `explicit stop is not marked as internal release`() {
        val output = PausedAudioOutput(player)
        update(output)
        state = Player.STATE_IDLE
        update(output)
        advance(4)
        assertEquals(0, stops)
        assertFalse(output.released)
        output.close()
    }

    @Test
    fun `closing cancels pending release`() {
        val output = PausedAudioOutput(player)
        update(output)
        output.close()
        advance(4)
        assertEquals(0, stops)
    }
}
