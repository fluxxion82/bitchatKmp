@file:OptIn(ExperimentalAtomicApi::class)

package com.bitchat.client.harness

import io.ktor.util.encodeBase64
import io.ktor.util.sha1
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import platform.posix.ECONNRESET
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** What the local server does with each connection after reading the request head. */
internal sealed interface ServerScript {
    /** Complete the WebSocket upgrade (101 + Sec-WebSocket-Accept), then hold the socket open. */
    data object WebSocketUpgrade : ServerScript

    /** Never answer: the client's handshake or request stays pending. */
    data object HoldAfterRequest : ServerScript

    /** Answer 200 "ok" with keep-alive and hold the socket open so it idles in the client's pool. */
    data object KeepAliveOk : ServerScript
}

/** One accepted connection and when the server observed what, on the monotonic clock. */
internal class ServerConnection(val index: Int) {
    val requestLine = AtomicReference<String?>(null)
    val requestAt = AtomicReference<TimeSource.Monotonic.ValueTimeMark?>(null)
    val respondedAt = AtomicReference<TimeSource.Monotonic.ValueTimeMark?>(null)
    val closeObservedAt = AtomicReference<TimeSource.Monotonic.ValueTimeMark?>(null)
    val closeKind = AtomicReference<String?>(null)
    val bytesAfterResponse = AtomicInt(0)

    override fun toString(): String =
        "conn#$index[${requestLine.load()} responded=${respondedAt.load() != null} close=${closeKind.load() ?: "open"} " +
            "bytesAfter=${bytesAfterResponse.load()}]"
}

/**
 * A plain HTTP/1.1 and WebSocket-upgrade server on 127.0.0.1:0 whose only job is to report when the
 * client's socket goes away (EOF or RST), on its own IO thread per connection.
 */
internal class LocalScriptedServer(private val script: ServerScript) {
    private val listener = PosixNet.tcpListener()
    val port: Int = PosixNet.boundPort(listener)

    private val stopped = AtomicBoolean(false)
    private val connections = AtomicReference<List<ServerConnection>>(emptyList())
    private val failures = AtomicReference<List<String>>(emptyList())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch { acceptLoop() }
    }

    fun connections(): List<ServerConnection> = connections.load()
    fun failures(): List<String> = failures.load()

    suspend fun awaitRequests(count: Int, timeout: Duration): Boolean =
        withTimeoutOrNull(timeout) {
            while (connections().count { it.requestAt.load() != null } < count) delay(10)
            true
        } ?: false

    suspend fun awaitResponded(index: Int, timeout: Duration): Boolean =
        withTimeoutOrNull(timeout) {
            while (connections().getOrNull(index)?.respondedAt?.load() == null) delay(10)
            true
        } ?: false

    suspend fun awaitClose(index: Int, timeout: Duration): Boolean =
        withTimeoutOrNull(timeout) {
            while (connections().getOrNull(index)?.closeObservedAt?.load() == null) delay(5)
            true
        } ?: false

    suspend fun awaitBytesAfterResponse(index: Int, minimum: Int, timeout: Duration): Boolean =
        withTimeoutOrNull(timeout) {
            while ((connections().getOrNull(index)?.bytesAfterResponse?.load() ?: 0) < minimum) delay(5)
            true
        } ?: false

    fun describe(): String = "server[:$port ${script::class.simpleName} ${connections()}${if (failures().isNotEmpty()) " failures=${failures()}" else ""}]"

    suspend fun stop() {
        stopped.store(true)
        withTimeoutOrNull(5.seconds) { scope.coroutineContext[Job]?.children?.toList()?.joinAll() }
        scope.cancel()
    }

    private fun acceptLoop() {
        try {
            while (!stopped.load()) {
                if (!PosixNet.readable(listener)) continue
                val client = PosixNet.acceptClient(listener)
                if (client < 0) continue
                val record = ServerConnection(connections().size)
                while (true) {
                    val current = connections.load()
                    if (connections.compareAndSet(current, current + record)) break
                }
                scope.launch { handle(client, record) }
            }
        } catch (error: Throwable) {
            fail("accept loop: ${error.message}")
        } finally {
            PosixNet.closeFd(listener)
        }
    }

    private fun handle(fd: Int, record: ServerConnection) {
        try {
            val deadline = TimeSource.Monotonic.markNow() + 15.seconds
            val head = PosixNet.readUntil(fd, "\r\n\r\n".encodeToByteArray(), 64 * 1024, deadline) { stopped.load() }
            if (head == null) {
                observeClose(fd, record, "closed before a request head arrived")
                return
            }
            record.requestAt.store(TimeSource.Monotonic.markNow())
            val lines = head.decodeToString().split("\r\n").filter { it.isNotEmpty() }
            record.requestLine.store(lines.firstOrNull())
            val headers = lines.drop(1).mapNotNull { line ->
                val separator = line.indexOf(':')
                if (separator <= 0) null else line.substring(0, separator).trim().lowercase() to line.substring(separator + 1).trim()
            }.toMap()

            when (script) {
                ServerScript.WebSocketUpgrade -> {
                    val key = headers["sec-websocket-key"] ?: error("no Sec-WebSocket-Key in ${record.requestLine.load()}")
                    val accept = sha1("$key$WEBSOCKET_GUID".encodeToByteArray()).encodeBase64()
                    write(
                        fd,
                        "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                            "Sec-WebSocket-Accept: $accept\r\n\r\n",
                    )
                    record.respondedAt.store(TimeSource.Monotonic.markNow())
                }
                ServerScript.KeepAliveOk -> {
                    write(fd, "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 2\r\nConnection: keep-alive\r\n\r\nok")
                    record.respondedAt.store(TimeSource.Monotonic.markNow())
                }
                ServerScript.HoldAfterRequest -> Unit
            }
            observeClose(fd, record, null)
        } catch (error: Throwable) {
            fail("${record}: ${error.message}")
        } finally {
            PosixNet.closeFd(fd)
        }
    }

    /** Reads (and discards) anything the client still sends until EOF or RST, stamping the moment. */
    private fun observeClose(fd: Int, record: ServerConnection, note: String?) {
        while (!stopped.load()) {
            if (!PosixNet.readable(fd, 20)) continue
            val received = PosixNet.readSome(fd)
            when {
                received == 0 -> {
                    record.closeObservedAt.store(TimeSource.Monotonic.markNow())
                    record.closeKind.store(note ?: "EOF")
                    return
                }
                received < 0 -> {
                    val errno = PosixNet.lastErrno()
                    record.closeObservedAt.store(TimeSource.Monotonic.markNow())
                    record.closeKind.store(if (errno == ECONNRESET) "RST" else "error($errno)")
                    return
                }
                else -> record.bytesAfterResponse.addAndFetch(received)
            }
        }
    }

    private fun write(fd: Int, text: String) {
        check(PosixNet.writeAll(fd, text.encodeToByteArray())) { "write failed: ${PosixNet.lastError()}" }
    }

    private fun fail(message: String) {
        while (true) {
            val current = failures.load()
            if (failures.compareAndSet(current, current + message)) return
        }
    }

    private companion object {
        const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    }
}
