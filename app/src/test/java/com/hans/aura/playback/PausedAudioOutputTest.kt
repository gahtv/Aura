package com.hans.aura.playback

import androidx.media3.common.Player
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PausedAudioOutputTest {
    @Test
    fun `encoded surround formats release the paused output`() {
        listOf(
            "audio/eac3", "audio/eac3-joc", "audio/ac3", "audio/ac4",
            "audio/vnd.dts", "audio/vnd.dts.hd", "audio/vnd.dts.hd;profile=lbr",
            "audio/vnd.dts.uhd", "audio/vnd.dts.uhd;profile=p2", "audio/true-hd"
        ).forEach { assertTrue(it, shouldReleasePausedAudioOutput(it)) }
    }

    @Test
    fun `PCM and other decoded formats keep the paused output`() {
        listOf("audio/raw", "audio/flac", "audio/alac", "audio/mpeg", "audio/mp4a-latm")
            .forEach { assertFalse(it, shouldReleasePausedAudioOutput(it)) }
    }

    @Test
    fun `missing and invalid formats keep the paused output`() {
        listOf(null, "", "unknown", "video/eac3", "audio/")
            .forEach { assertFalse(it, shouldReleasePausedAudioOutput(it)) }
    }

    @Test
    fun `idle remote play prepares before playing`() {
        assertEquals(listOf("prepare", "play"), playCalls(Player.STATE_IDLE))
    }

    @Test
    fun `prepared remote play does not prepare again`() {
        listOf(Player.STATE_READY, Player.STATE_BUFFERING, Player.STATE_ENDED).forEach {
            assertEquals(listOf("play"), playCalls(it))
        }
    }

    private fun playCalls(state: Int): List<String> {
        val calls = mutableListOf<String>()
        val player = Proxy.newProxyInstance(
            Player::class.java.classLoader, arrayOf(Player::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getPlaybackState" -> state
                "prepare", "play" -> { calls.add(method.name); null }
                else -> error("Unexpected call: ${method.name}")
            }
        } as Player
        player.playPrepared()
        return calls
    }
}
