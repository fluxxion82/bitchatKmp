@file:OptIn(ExperimentalAtomicApi::class)

package com.bitchat.client.harness

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
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** One SOCKS5 CONNECT as the proxy saw it: the client's own address encoding, unresolved. */
internal data class SocksConnect(
    val greetingMethods: List<Int>,
    val addressType: Int,
    val host: String,
    val port: Int,
) {
    val isDomain: Boolean get() = addressType == SocksCaptureServer.ATYP_DOMAIN

    override fun toString(): String =
        "CONNECT atyp=$addressType host=$host port=$port methods=$greetingMethods"
}

internal sealed interface SocksMode {
    /** Record CONNECT, reply general failure (0x01), close. Any destination traffic is a bypass. */
    data object Close : SocksMode

    /** Record CONNECT, reply connection refused (0x05), close. */
    data object Refuse : SocksMode

    /** Reply CONNECT success, then immediately EOF: exercises post-CONNECT failure handling. */
    data object EofAfterConnect : SocksMode

    /** Read the greeting and never answer it; the handshake stays pending until the server stops. */
    data object Hold : SocksMode

    /** Reply success, then answer plain HTTP/1.1 on the tunnel (one request per connection). */
    class ServeHttp(val handler: (TunnelHttpRequest) -> TunnelHttpReply) : SocksMode

    /** Reply success, then transparently relay bytes to a loopback scripted server until dropped. */
    class TunnelToLocalServer(val port: Int) : SocksMode
}

internal class TunnelHttpRequest(val method: String, val target: String, val headers: Map<String, String>) {
    val host: String get() = headers["host"]?.substringBefore(':')?.trim() ?: ""

    override fun toString(): String = "$method $target host=$host"
}

internal class TunnelHttpReply(
    private val status: Int,
    private val reason: String,
    private val headers: List<Pair<String, String>>,
    private val body: ByteArray,
) {
    fun encode(): ByteArray {
        val head = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            headers.forEach { (name, value) -> append("$name: $value\r\n") }
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        return head.encodeToByteArray() + body
    }

    companion object {
        fun redirect(location: String) = TunnelHttpReply(302, "Found", listOf("Location" to location), ByteArray(0))

        fun ok(text: String) = TunnelHttpReply(200, "OK", listOf("Content-Type" to "text/plain"), text.encodeToByteArray())
    }
}

/**
 * A SOCKS5 server on 127.0.0.1:0 that records what the client asks for and never resolves or
 * connects anywhere. Each accepted connection is handled on its own IO thread so a held or slow
 * handshake cannot delay another capture.
 */
internal class SocksCaptureServer(private val mode: SocksMode) {
    private val listener = PosixNet.tcpListener()
    val port: Int = PosixNet.boundPort(listener)

    private val stopped = AtomicBoolean(false)
    private val acceptedCount = AtomicInt(0)
    private val connects = AtomicReference<List<SocksConnect>>(emptyList())
    private val greetings = AtomicReference<List<List<Int>>>(emptyList())
    private val httpRequests = AtomicReference<List<TunnelHttpRequest>>(emptyList())
    private val failures = AtomicReference<List<String>>(emptyList())
    private val tunnelGeneration = AtomicInt(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch { acceptLoop() }
    }

    fun accepted(): Int = acceptedCount.load()
    fun connects(): List<SocksConnect> = connects.load()
    fun greetings(): List<List<Int>> = greetings.load()
    fun httpRequests(): List<TunnelHttpRequest> = httpRequests.load()
    fun failures(): List<String> = failures.load()

    /** Drops already-established tunnels; a later CONNECT gets the next generation and remains live. */
    fun dropActiveTunnels() {
        tunnelGeneration.addAndFetch(1)
    }

    suspend fun awaitConnects(count: Int, timeout: Duration): Boolean =
        withTimeoutOrNull(timeout) {
            while (connects().size < count) delay(25)
            true
        } ?: false

    suspend fun awaitAccepted(count: Int, timeout: Duration): Boolean =
        withTimeoutOrNull(timeout) {
            while (accepted() < count) delay(25)
            true
        } ?: false

    fun describe(): String = buildString {
        append("socks[:$port mode=${mode::class.simpleName} accepted=${accepted()} connects=${connects()}")
        if (httpRequests().isNotEmpty()) append(" http=${httpRequests()}")
        if (failures().isNotEmpty()) append(" failures=${failures()}")
        append("]")
    }

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
                acceptedCount.addAndFetch(1)
                scope.launch { handle(client) }
            }
        } catch (error: Throwable) {
            record(failures, "accept loop: ${error.message}")
        } finally {
            PosixNet.closeFd(listener)
        }
    }

    private fun handle(fd: Int) {
        val deadline = TimeSource.Monotonic.markNow() + HANDSHAKE_BOUND
        try {
            val greeting = readExact(fd, 2, deadline) ?: return
            check(greeting[0].toInt() == 5) { "greeting version ${greeting[0]}" }
            val methods = readExact(fd, greeting[1].toInt() and 0xFF, deadline)?.map { it.toInt() and 0xFF } ?: return
            record(greetings, methods)
            if (mode is SocksMode.Hold) {
                while (!stopped.load()) PosixNet.readable(fd)
                return
            }
            write(fd, byteArrayOf(5, 0))

            val request = readExact(fd, 4, deadline) ?: return
            check(request[0].toInt() == 5) { "request version ${request[0]}" }
            check(request[1].toInt() == 1) { "command ${request[1]} is not CONNECT" }
            val addressType = request[3].toInt() and 0xFF
            val host = when (addressType) {
                ATYP_IPV4 -> readExact(fd, 4, deadline)?.joinToString(".") { (it.toInt() and 0xFF).toString() }
                ATYP_DOMAIN -> {
                    val length = readExact(fd, 1, deadline)?.get(0)?.toInt()?.and(0xFF) ?: return
                    readExact(fd, length, deadline)?.decodeToString()
                }
                ATYP_IPV6 -> readExact(fd, 16, deadline)?.toList()?.chunked(2)
                    ?.joinToString(":") { (((it[0].toInt() and 0xFF) shl 8) or (it[1].toInt() and 0xFF)).toString(16) }
                else -> error("unknown address type $addressType")
            } ?: return
            val portBytes = readExact(fd, 2, deadline) ?: return
            val port = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)
            record(connects, SocksConnect(methods, addressType, host, port))

            when (mode) {
                SocksMode.Close -> write(fd, reply(REP_GENERAL_FAILURE))
                SocksMode.Refuse -> write(fd, reply(REP_CONNECTION_REFUSED))
                SocksMode.EofAfterConnect -> write(fd, reply(REP_SUCCEEDED))
                is SocksMode.ServeHttp -> {
                    write(fd, reply(REP_SUCCEEDED))
                    serveHttp(fd, mode.handler, deadline)
                }
                is SocksMode.TunnelToLocalServer -> {
                    write(fd, reply(REP_SUCCEEDED))
                    tunnelToLocalServer(fd, mode.port, tunnelGeneration.load())
                }
                SocksMode.Hold -> Unit
            }
        } catch (error: Throwable) {
            record(failures, error.message ?: error.toString())
        } finally {
            PosixNet.closeFd(fd)
        }
    }

    private fun serveHttp(fd: Int, handler: (TunnelHttpRequest) -> TunnelHttpReply, deadline: TimeMark) {
        val head = PosixNet.readUntil(fd, "\r\n\r\n".encodeToByteArray(), 16 * 1024, deadline) { stopped.load() }
            ?: run { record(failures, "no HTTP request head on tunnel"); return }
        val lines = head.decodeToString().split("\r\n").filter { it.isNotEmpty() }
        val requestLine = lines.firstOrNull()?.split(' ') ?: emptyList()
        check(requestLine.size >= 2) { "bad request line ${lines.firstOrNull()}" }
        val headers = lines.drop(1).mapNotNull { line ->
            val separator = line.indexOf(':')
            if (separator <= 0) null else line.substring(0, separator).trim().lowercase() to line.substring(separator + 1).trim()
        }.toMap()
        val request = TunnelHttpRequest(requestLine[0], requestLine[1], headers)
        record(httpRequests, request)
        write(fd, handler(request).encode())
    }

    private fun tunnelToLocalServer(client: Int, port: Int, generation: Int) {
        val endpoint = PosixNet.connectLoopback(port)
        try {
            while (!stopped.load() && tunnelGeneration.load() == generation) {
                if (PosixNet.readable(client, 10) && PosixNet.relayOnce(client, endpoint) <= 0) return
                if (PosixNet.readable(endpoint, 0) && PosixNet.relayOnce(endpoint, client) <= 0) return
            }
        } finally {
            PosixNet.closeFd(endpoint)
        }
    }

    private fun readExact(fd: Int, count: Int, deadline: TimeMark): ByteArray? =
        PosixNet.readExact(fd, count, deadline) { stopped.load() }

    private fun write(fd: Int, bytes: ByteArray) {
        check(PosixNet.writeAll(fd, bytes)) { "write failed: ${PosixNet.lastError()}" }
    }

    private fun reply(code: Byte): ByteArray = byteArrayOf(5, code, 0, ATYP_IPV4.toByte(), 0, 0, 0, 0, 0, 0)

    private fun <T> record(target: AtomicReference<List<T>>, item: T) {
        while (true) {
            val current = target.load()
            if (target.compareAndSet(current, current + item)) return
        }
    }

    companion object {
        const val ATYP_IPV4 = 1
        const val ATYP_DOMAIN = 3
        const val ATYP_IPV6 = 4

        private const val REP_SUCCEEDED: Byte = 0
        private const val REP_GENERAL_FAILURE: Byte = 1
        private const val REP_CONNECTION_REFUSED: Byte = 5
        private val HANDSHAKE_BOUND = 20.seconds
    }
}
