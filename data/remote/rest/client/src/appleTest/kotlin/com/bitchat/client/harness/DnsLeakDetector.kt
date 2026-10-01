@file:OptIn(ExperimentalAtomicApi::class, ExperimentalForeignApi::class)

package com.bitchat.client.harness

import kotlinx.cinterop.ExperimentalForeignApi
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
import platform.Foundation.NSUUID
import platform.posix.R_OK
import platform.posix.access
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal data class DnsQuery(val name: String, val type: Int) {
    override fun toString(): String = "$name/${typeName(type)}"

    companion object {
        fun typeName(type: Int): String = when (type) {
            1 -> "A"
            28 -> "AAAA"
            65 -> "HTTPS"
            else -> "TYPE$type"
        }
    }
}

/**
 * The local-resolution leak detector.
 *
 * A UDP DNS server on 127.0.0.1:5533 that logs every query name and type and answers A with
 * 127.0.0.1 (NOERROR with no data for everything else). The owner's resolver file
 * `/etc/resolver/bitchat-leak.test` (`nameserver 127.0.0.1`, `port 5533`) sends every lookup under
 * `bitchat-leak.test` here, so a probe name resolved locally by CFNetwork/mDNSResponder is recorded.
 * Without that file the detector is NOT wired: dependent assertions must skip loudly, never pass.
 */
internal class DnsLeakDetector private constructor(private val socket: Int, val bindError: String?) {
    val resolverFilePresent: Boolean = access(RESOLVER_FILE, R_OK) == 0
    val wired: Boolean get() = resolverFilePresent && socket >= 0

    private val stopped = AtomicBoolean(false)
    private val queries = AtomicReference<List<DnsQuery>>(emptyList())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        if (socket >= 0) scope.launch { serveLoop() }
    }

    fun allQueries(): List<DnsQuery> = queries.load()

    fun queriesFor(name: String): List<DnsQuery> =
        allQueries().filter { it.name.equals(name.trimEnd('.'), ignoreCase = true) }

    suspend fun awaitQuery(name: String, timeout: Duration): Boolean =
        withTimeoutOrNull(timeout) {
            while (queriesFor(name).isEmpty()) delay(25)
            true
        } ?: false

    /** Why the detector cannot observe, or null when it can. */
    fun notWiredReason(): String? = when {
        !resolverFilePresent -> NOT_WIRED_MESSAGE
        socket < 0 -> "LEAK DETECTOR NOT WIRED: cannot bind 127.0.0.1:$PORT ($bindError)"
        else -> null
    }

    fun describe(): String = "dns[:$PORT wired=$wired queries=${allQueries()}]"

    suspend fun stop() {
        stopped.store(true)
        withTimeoutOrNull(5.seconds) { scope.coroutineContext[Job]?.children?.toList()?.joinAll() }
        scope.cancel()
    }

    private fun serveLoop() {
        try {
            while (!stopped.load()) {
                PosixNet.serveDatagram(socket, 100) { packet -> answer(packet) }
            }
        } finally {
            PosixNet.closeFd(socket)
        }
    }

    private fun answer(packet: ByteArray): ByteArray? {
        if (packet.size < 12) return null
        val questionCount = packet.u16(4)
        if (questionCount < 1) return null
        var offset = 12
        val labels = mutableListOf<String>()
        while (offset < packet.size) {
            val length = packet[offset].toInt() and 0xFF
            offset += 1
            if (length == 0) break
            if (length >= 0xC0 || offset + length > packet.size) return null
            labels += packet.decodeToString(offset, offset + length)
            offset += length
        }
        if (offset + 4 > packet.size) return null
        val type = packet.u16(offset)
        val questionEnd = offset + 4
        val name = labels.joinToString(".").lowercase()
        record(DnsQuery(name, type))

        val answers = if (type == TYPE_A) 1 else 0
        val header = byteArrayOf(
            packet[0], packet[1], // id
            0x81.toByte(), 0x80.toByte(), // QR, RD, RA, NOERROR
            0, 1, // QDCOUNT
            0, answers.toByte(), // ANCOUNT
            0, 0, // NSCOUNT
            0, 0, // ARCOUNT
        )
        val question = packet.copyOfRange(12, questionEnd)
        val answer = if (answers == 1) {
            byteArrayOf(
                0xC0.toByte(), 0x0C, // pointer to the question name
                0, 1, 0, 1, // A, IN
                0, 0, 0, 0, // TTL 0
                0, 4, 127, 0, 0, 1,
            )
        } else {
            ByteArray(0)
        }
        return header + question + answer
    }

    private fun record(query: DnsQuery) {
        while (true) {
            val current = queries.load()
            if (queries.compareAndSet(current, current + query)) return
        }
    }

    private fun ByteArray.u16(index: Int): Int = ((this[index].toInt() and 0xFF) shl 8) or (this[index + 1].toInt() and 0xFF)

    companion object {
        const val RESOLVER_FILE = "/etc/resolver/bitchat-leak.test"
        const val SUFFIX = "bitchat-leak.test"
        const val PORT = 5533
        const val NOT_WIRED_MESSAGE = "LEAK DETECTOR NOT WIRED: /etc/resolver/bitchat-leak.test missing"
        private const val TYPE_A = 1

        /**
         * Binds the detector port, waiting out another suite that still holds it (the macOS and
         * simulator suites share the host's loopback). With the resolver file present a port that
         * stays busy is an error, not a skip: the leak checks would silently not run.
         */
        fun start(): DnsLeakDetector {
            val resolverFilePresent = access(RESOLVER_FILE, R_OK) == 0
            var socket = runCatching { PosixNet.udpSocket(PORT) }
            var waited = 0
            while (socket.isFailure && resolverFilePresent && waited < BIND_WAIT_MILLIS) {
                platform.posix.usleep(BIND_RETRY_MILLIS * 1000u)
                waited += BIND_RETRY_MILLIS.toInt()
                socket = runCatching { PosixNet.udpSocket(PORT) }
            }
            check(socket.isSuccess || !resolverFilePresent) {
                "leak detector port $PORT stayed busy for ${BIND_WAIT_MILLIS / 1000} s: " +
                    socket.exceptionOrNull()?.message
            }
            return DnsLeakDetector(socket.getOrDefault(-1), socket.exceptionOrNull()?.message)
        }

        private const val BIND_WAIT_MILLIS = 60_000
        private const val BIND_RETRY_MILLIS = 250u

        /** A never-before-seen name under the detector's suffix. */
        fun freshName(): String = "${NSUUID().UUIDString.lowercase()}.$SUFFIX"
    }
}
