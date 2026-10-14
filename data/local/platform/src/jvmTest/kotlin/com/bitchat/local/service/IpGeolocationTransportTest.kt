package com.bitchat.local.service

import kotlinx.coroutines.runBlocking
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IpGeolocationTransportTest {
    @Test
    fun `an answer of sixteen kibibytes is returned whole and a longer one is no answer`() = runBlocking {
        val body = "x".repeat(16 * 1024)
        LoopbackServer.responding(200, body).use { server ->
            assertEquals(body, IpGeolocation.get(server.url) { true })
        }
        LoopbackServer.responding(200, body + "x").use { server ->
            assertNull(IpGeolocation.get(server.url) { true })
        }
    }

    @Test
    fun `follows a redirect to its two hundred body`() = runBlocking {
        LoopbackServer.responding(200, "redirected").use { destination ->
            LoopbackServer.responding(302, "", "Location: ${destination.url}\r\n").use { redirect ->
                assertEquals("redirected", IpGeolocation.get(redirect.url) { true })
            }
        }
    }

    @Test
    fun `refusing admission does not connect`() = runBlocking {
        LoopbackServer.responding(200, "unused").use { server ->
            assertNull(IpGeolocation.get(server.url) { false })
            assertTrue(server.awaitNoConnection())
        }
    }

    @Test
    fun `a non two hundred response is no answer`() = runBlocking {
        LoopbackServer.responding(404, "missing").use { server ->
            assertNull(IpGeolocation.get(server.url) { true })
        }
    }

    private class LoopbackServer private constructor(private val reply: (Socket) -> Unit) : AutoCloseable {
        private val server = ServerSocket(0).apply { soTimeout = 500 }
        private val finished = CountDownLatch(1)
        private val connections = AtomicInteger()
        private val worker = thread(isDaemon = true) {
            try {
                server.accept().use { socket ->
                    connections.incrementAndGet()
                    reply(socket)
                }
            } catch (_: SocketException) {
            } catch (_: SocketTimeoutException) {
            } finally {
                finished.countDown()
            }
        }

        val url get() = "http://127.0.0.1:${server.localPort}/"

        fun awaitNoConnection(): Boolean = finished.await(2, TimeUnit.SECONDS) && connections.get() == 0

        override fun close() {
            server.close()
            worker.join(1_000)
        }

        companion object {
            fun responding(status: Int, body: String, headers: String = ""): LoopbackServer = LoopbackServer { socket ->
                socket.inputStream.readHeaders()
                val bytes = body.encodeToByteArray()
                socket.getOutputStream().apply {
                    write("HTTP/1.1 $status Test\r\nContent-Length: ${bytes.size}\r\n$headers\r\n".encodeToByteArray())
                    write(bytes)
                    flush()
                }
            }

            private fun InputStream.readHeaders() {
                var window = 0
                while (window != 0x0d0a0d0a) {
                    val byte = read()
                    if (byte < 0) return
                    window = (window shl 8) or byte
                }
            }
        }
    }
}
