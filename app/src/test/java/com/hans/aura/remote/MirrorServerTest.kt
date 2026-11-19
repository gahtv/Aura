package com.hans.aura.remote

import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class MirrorServerTest {
    @Test
    fun `half sent header does not block health and is eventually closed`() {
        MirrorServer.start()
        val deadline = System.nanoTime() + 5_000_000_000L
        var stalled: Socket? = null
        while (stalled == null) {
            try {
                stalled = Socket("127.0.0.1", 8099)
            } catch (e: java.net.ConnectException) {
                if (System.nanoTime() >= deadline) throw e
                Thread.yield()
            }
        }
        stalled.use { socket ->
            socket.getOutputStream().write("GET /health HTTP/1.0\r\nX-Stalled: half".toByteArray())
            socket.getOutputStream().flush()
            // Give the accept loop time to enter the incomplete header read.
            Thread.sleep(100)
            Socket("127.0.0.1", 8099).use { healthy ->
                healthy.soTimeout = 750
                healthy.getOutputStream().write("GET /health HTTP/1.0\r\n\r\n".toByteArray())
                val response = healthy.getInputStream().bufferedReader().readText()
                assertTrue(response.startsWith("HTTP/1.0 200\r\n"))
                assertTrue(response.endsWith("\r\n\r\nok"))
            }
            socket.soTimeout = 5_000
            assertEquals(-1, socket.getInputStream().read())
        }

        Socket("127.0.0.1", 8099).use { empty ->
            empty.shutdownOutput()
            empty.soTimeout = 750
            assertEquals(-1, empty.getInputStream().read())
        }
        Socket("127.0.0.1", 8099).use { unauthorised ->
            unauthorised.soTimeout = 750
            unauthorised.getOutputStream().write("GET /state HTTP/1.0\r\n\r\n".toByteArray())
            assertTrue(unauthorised.getInputStream().bufferedReader().readText()
                .startsWith("HTTP/1.0 401\r\n"))
        }

        Socket("127.0.0.1", 8099).use { pairing ->
            pairing.getOutputStream().write("POST /pair/request?name=Test HTTP/1.0\r\n\r\n".toByteArray())
            val promptDeadline = System.nanoTime() + 5_000_000_000L
            while (MirrorServer.pairing.value == null) {
                assertTrue("Pairing prompt not shown", System.nanoTime() < promptDeadline)
                Thread.yield()
            }
            Thread.sleep(2_200)
            Socket("127.0.0.1", 8099).use { healthy ->
                healthy.soTimeout = 750
                healthy.getOutputStream().write("GET /health HTTP/1.0\r\n\r\n".toByteArray())
                assertTrue(healthy.getInputStream().bufferedReader().readText().endsWith("ok"))
            }
            MirrorServer.answerPairing(true)
            pairing.soTimeout = 750
            val response = pairing.getInputStream().bufferedReader().readText()
            assertTrue(response.startsWith("HTTP/1.0 200\r\n"))
            assertTrue(response.contains("\"token\":"))
        }
    }
}
