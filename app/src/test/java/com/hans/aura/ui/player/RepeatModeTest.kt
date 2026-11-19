package com.hans.aura.ui.player

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test

class RepeatModeTest {

    @Test
    fun `repeat cycles from off to all to one and back to off`() {
        val all = nextRepeatMode(Player.REPEAT_MODE_OFF)
        assertEquals("Off should advance to All", Player.REPEAT_MODE_ALL, all)

        val one = nextRepeatMode(all)
        assertEquals("All should advance to One", Player.REPEAT_MODE_ONE, one)

        assertEquals("One should advance to Off", Player.REPEAT_MODE_OFF, nextRepeatMode(one))
    }
}
