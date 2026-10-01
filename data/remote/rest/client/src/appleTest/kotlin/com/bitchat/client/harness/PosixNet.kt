@file:OptIn(ExperimentalForeignApi::class)

package com.bitchat.client.harness

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.AF_INET
import platform.posix.EINTR
import platform.posix.POLLIN
import platform.posix.SHUT_RDWR
import platform.posix.SIGPIPE
import platform.posix.SIG_IGN
import platform.posix.SOCK_DGRAM
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_NOSIGPIPE
import platform.posix.SO_REUSEADDR
import platform.posix.__error
import platform.posix.accept
import platform.posix.bind
import platform.posix.close
import platform.posix.connect
import platform.posix.getsockname
import platform.posix.listen
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.recv
import platform.posix.recvfrom
import platform.posix.send
import platform.posix.sendto
import platform.posix.setsockopt
import platform.posix.shutdown
import platform.posix.signal
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_tVar
import platform.posix.strerror
import kotlin.time.TimeMark

/**
 * Blocking loopback socket helpers for the Darwin capture harness.
 *
 * Every wait is a bounded `poll`, so a server thread can observe a stop flag between polls: on
 * Darwin, closing a descriptor from another thread does not reliably wake a blocked `poll`.
 */
internal object PosixNet {
    private const val POLL_SLICE_MS = 100

    /** 127.0.0.1 in network byte order, as stored on the little-endian Apple targets. */
    private const val LOOPBACK_NETWORK_ORDER: UInt = 0x0100007Fu

    fun ignoreSigpipe() {
        signal(SIGPIPE, SIG_IGN)
    }

    fun lastError(): String {
        val code = __error()?.pointed?.value ?: 0
        return "${strerror(code)?.toKString() ?: "unknown"} ($code)"
    }

    fun tcpListener(port: Int = 0, backlog: Int = 16): Int {
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        check(fd >= 0) { "socket: ${lastError()}" }
        setOption(fd, SO_REUSEADDR)
        setOption(fd, SO_NOSIGPIPE)
        bindLoopback(fd, port)
        check(listen(fd, backlog) == 0) { "listen: ${lastError()}" }
        return fd
    }

    fun udpSocket(port: Int): Int {
        val fd = socket(AF_INET, SOCK_DGRAM, 0)
        check(fd >= 0) { "socket: ${lastError()}" }
        setOption(fd, SO_REUSEADDR)
        bindLoopback(fd, port)
        return fd
    }

    fun boundPort(fd: Int): Int = memScoped {
        val address = alloc<sockaddr_in>()
        val length = alloc<socklen_tVar>()
        length.value = sizeOf<sockaddr_in>().convert()
        check(getsockname(fd, address.ptr.reinterpret(), length.ptr) == 0) { "getsockname: ${lastError()}" }
        hostOrder(address.sin_port)
    }

    /** True when [fd] is readable (or hung up) within [timeoutMs]; false on timeout. */
    fun readable(fd: Int, timeoutMs: Int = POLL_SLICE_MS): Boolean = memScoped {
        val descriptor = alloc<pollfd>()
        descriptor.fd = fd
        descriptor.events = POLLIN.toShort()
        descriptor.revents = 0
        val ready = poll(descriptor.ptr, 1u, timeoutMs)
        if (ready < 0) {
            val code = __error()?.pointed?.value ?: 0
            check(code == EINTR) { "poll: ${lastError()}" }
            return false
        }
        ready > 0
    }

    fun acceptClient(listener: Int): Int {
        val client = accept(listener, null, null)
        if (client >= 0) setOption(client, SO_NOSIGPIPE)
        return client
    }

    /** Connects only to the local scripted endpoint; SOCKS destination names are never resolved here. */
    fun connectLoopback(port: Int): Int = memScoped {
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        check(fd >= 0) { "socket: ${lastError()}" }
        setOption(fd, SO_NOSIGPIPE)
        val address = loopbackAddress(port)
        if (connect(fd, address.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) != 0) {
            val error = lastError()
            close(fd)
            error("connect 127.0.0.1:$port: $error")
        }
        fd
    }

    /** Exactly [count] bytes, or null on EOF, error, [deadline] or [stop]. */
    fun readExact(fd: Int, count: Int, deadline: TimeMark, stop: () -> Boolean): ByteArray? {
        val out = ByteArray(count)
        var offset = 0
        while (offset < count) {
            if (stop() || deadline.hasPassedNow()) return null
            if (!readable(fd)) continue
            val received = out.usePinned { pinned ->
                recv(fd, pinned.addressOf(offset), (count - offset).convert(), 0)
            }
            if (received <= 0) return null
            offset += received.toInt()
        }
        return out
    }

    /** Bytes up to and including the first [delimiter], or null on EOF, overflow, [deadline] or [stop]. */
    fun readUntil(fd: Int, delimiter: ByteArray, maxBytes: Int, deadline: TimeMark, stop: () -> Boolean): ByteArray? {
        val buffer = ByteArray(maxBytes)
        var length = 0
        while (true) {
            val end = indexOf(buffer, length, delimiter)
            if (end >= 0) return buffer.copyOf(end + delimiter.size)
            if (length >= maxBytes || stop() || deadline.hasPassedNow()) return null
            if (!readable(fd)) continue
            val received = buffer.usePinned { pinned ->
                recv(fd, pinned.addressOf(length), (maxBytes - length).convert(), 0)
            }
            if (received <= 0) return null
            length += received.toInt()
        }
    }

    /** One `recv`: bytes read, 0 on EOF, negative on error (see [lastErrno]). Caller polls first. */
    fun readSome(fd: Int, max: Int = 4096): Int {
        val buffer = ByteArray(max)
        val received = buffer.usePinned { pinned -> recv(fd, pinned.addressOf(0), max.convert(), 0) }
        return received.toInt()
    }

    fun lastErrno(): Int = __error()?.pointed?.value ?: 0

    fun writeAll(fd: Int, bytes: ByteArray): Boolean {
        var offset = 0
        while (offset < bytes.size) {
            val sent = bytes.usePinned { pinned ->
                send(fd, pinned.addressOf(offset), (bytes.size - offset).convert(), 0)
            }
            if (sent <= 0) return false
            offset += sent.toInt()
        }
        return true
    }

    /** Forwards one available read from [source] to [destination]; 0 is EOF and negative is failure. */
    fun relayOnce(source: Int, destination: Int): Int {
        val buffer = ByteArray(4096)
        val received = buffer.usePinned { pinned -> recv(source, pinned.addressOf(0), buffer.size.convert(), 0) }.toInt()
        if (received > 0 && !writeAll(destination, buffer.copyOf(received))) return -1
        return received
    }

    /**
     * Serves one datagram if any arrives within [timeoutMs]: [handler] maps the payload to a reply
     * (null for none) sent back to the sender. Returns false when nothing arrived.
     */
    fun serveDatagram(fd: Int, timeoutMs: Int, handler: (ByteArray) -> ByteArray?): Boolean {
        if (!readable(fd, timeoutMs)) return false
        memScoped {
            val sender = alloc<sockaddr_in>()
            val senderLength = alloc<socklen_tVar>()
            senderLength.value = sizeOf<sockaddr_in>().convert()
            val buffer = ByteArray(1500)
            val received = buffer.usePinned { pinned ->
                recvfrom(fd, pinned.addressOf(0), buffer.size.convert(), 0, sender.ptr.reinterpret(), senderLength.ptr)
            }
            if (received <= 0) return true
            val reply = handler(buffer.copyOf(received.toInt())) ?: return true
            reply.usePinned { pinned ->
                sendto(fd, pinned.addressOf(0), reply.size.convert(), 0, sender.ptr.reinterpret(), senderLength.value)
            }
        }
        return true
    }

    fun closeFd(fd: Int) {
        if (fd < 0) return
        shutdown(fd, SHUT_RDWR)
        close(fd)
    }

    private fun setOption(fd: Int, option: Int, value: Int = 1) = memScoped {
        val holder = alloc<IntVar>()
        holder.value = value
        setsockopt(fd, SOL_SOCKET, option, holder.ptr, sizeOf<IntVar>().convert())
    }

    private fun bindLoopback(fd: Int, port: Int) = memScoped {
        val address = loopbackAddress(port)
        if (bind(fd, address.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) != 0) {
            val error = lastError()
            close(fd)
            error("bind 127.0.0.1:$port: $error")
        }
    }

    private fun MemScope.loopbackAddress(port: Int): sockaddr_in = alloc<sockaddr_in>().apply {
        sin_len = sizeOf<sockaddr_in>().convert()
        sin_family = AF_INET.convert()
        sin_port = networkOrder(port)
        sin_addr.s_addr = LOOPBACK_NETWORK_ORDER
    }

    private fun networkOrder(port: Int): UShort =
        (((port and 0xFF) shl 8) or ((port ushr 8) and 0xFF)).toUShort()

    private fun hostOrder(port: UShort): Int {
        val raw = port.toInt()
        return ((raw and 0xFF) shl 8) or ((raw ushr 8) and 0xFF)
    }

    private fun indexOf(haystack: ByteArray, length: Int, needle: ByteArray): Int {
        if (needle.isEmpty() || length < needle.size) return -1
        outer@ for (start in 0..length - needle.size) {
            for (i in needle.indices) {
                if (haystack[start + i] != needle[i]) continue@outer
            }
            return start
        }
        return -1
    }
}
