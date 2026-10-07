package com.bitchat.client.websocket

import com.bitchat.client.TorRouteProvenance
import com.bitchat.client.WebSocketRouteProvider
import io.ktor.client.HttpClient
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.fail

/** Waits for a state that has to come, and fails with [what] if it never does. No outcome depends on how long it takes. */
internal fun awaitUntil(what: String, condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
    while (!condition()) {
        if (System.nanoTime() > deadline) fail("never happened: $what")
        Thread.sleep(5)
    }
}

/** A route that is always current and never Tor: these tests are about what arrives, not how it got there. */
internal fun directRoutes(client: HttpClient) = object : WebSocketRouteProvider {
    private val route = object : TorRouteProvenance {
        override val usedTorProxy = false
        override fun isCurrent() = true
    }

    override suspend fun <T> useWebSocketRoute(block: suspend (HttpClient, TorRouteProvenance) -> T): T = block(client, route)
}

/** A listener that stays inside the first frame it is given until the test lets it go: the slow consumer. */
internal class HeldListener(
    private val onOpen: (String) -> Unit = {},
    private val onFrame: (String) -> Unit = {},
    private val onDrained: (String) -> Unit = {},
) : WebSocketListener {
    private val mayGoOn = CountDownLatch(1)
    private val received = Collections.synchronizedList(mutableListOf<String>())
    private val drainedNotices = CopyOnWriteArrayList<Long>()

    fun letGo() = mayGoOn.countDown()
    fun frames(): List<String> = synchronized(received) { received.toList() }

    /** What each "drained after drops" notice said was dropped, in the order they came. */
    fun drained(): List<Long> = drainedNotices.toList()

    override fun onBacklogDrained(url: String, framesDropped: Long) {
        onDrained.invoke(url)
        drainedNotices += framesDropped
    }

    override fun onOpen(url: String, route: TorRouteProvenance) = onOpen.invoke(url)
    override fun onMessage(url: String, text: String) {
        received += text
        onFrame.invoke(url)
        mayGoOn.await(60, TimeUnit.SECONDS)
    }

    override fun onClosing(url: String, code: Int, reason: String) = Unit
    override fun onClosed(url: String, code: Int, reason: String) = Unit
    override fun onFailure(url: String, t: Throwable) = Unit
}

/** A relay on the loopback interface that says exactly what the test tells it to, to whoever connected last. */
internal class LoopbackRelay : AutoCloseable {
    private val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
    private val accepted = CopyOnWriteArrayList<Socket>()
    val url = "ws://127.0.0.1:${server.localPort}/"

    init {
        thread(isDaemon = true) {
            runCatching {
                while (true) {
                    val socket = server.accept()
                    upgrade(socket)
                    accepted += socket
                }
            }
        }
    }

    fun connections(): Int = accepted.size

    /** Writes [texts] as text frames, as fast as the socket takes them, once someone is connected. */
    fun send(texts: List<String>) = sendTextFrames(texts.map { it.toByteArray() })

    /** Writes each of [payloads] as a text frame whatever is in it, valid text or not. */
    fun sendTextFrames(payloads: List<ByteArray>) {
        awaitUntil("someone connected to $url") { accepted.isNotEmpty() && !accepted.last().isClosed }
        val out = accepted.last().getOutputStream().buffered(1 shl 16)
        payloads.forEach { out.writeTextFrame(it) }
        out.flush()
    }

    fun dropConnection() = accepted.last().close()

    override fun close() {
        accepted.forEach { runCatching { it.close() } }
        server.close()
    }

    private fun upgrade(socket: Socket) {
        val input = socket.getInputStream()
        val request = StringBuilder()
        while (!request.endsWith("\r\n\r\n")) {
            val next = input.read()
            check(next >= 0) { "client closed during the handshake" }
            request.append(next.toChar())
        }
        val key = request.lines().first { it.startsWith("Sec-WebSocket-Key", ignoreCase = true) }.substringAfter(":").trim()
        val accept = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray())
        )
        socket.getOutputStream().apply {
            write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
            flush()
        }
    }

    private fun OutputStream.writeTextFrame(payload: ByteArray) {
        write(0x81)
        when {
            payload.size < 126 -> write(payload.size)
            payload.size <= 0xFFFF -> {
                write(126)
                write(payload.size shr 8)
                write(payload.size and 0xFF)
            }
            else -> {
                write(127)
                repeat(4) { write(0) }
                for (shift in 24 downTo 0 step 8) write((payload.size shr shift) and 0xFF)
            }
        }
        write(payload)
    }
}
