package com.bitchat.lora.meshcore

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import platform.posix.AF_INET
import platform.posix.EAGAIN
import platform.posix.EWOULDBLOCK
import platform.posix.IPPROTO_TCP
import platform.posix.SO_RCVTIMEO
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.TCP_NODELAY
import platform.posix.close
import platform.posix.connect
import platform.posix.errno
import platform.posix.htonl
import platform.posix.htons
import platform.posix.read
import platform.posix.setsockopt
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.timeval
import platform.posix.usleep
import platform.posix.write

/**
 * Linux ARM64 implementation of MeshCore serial using POSIX TCP sockets.
 *
 * Connects to meshcore-pi daemon running on localhost:5000.
 * meshcore-pi controls the SX1276/RFM95W radio via SPI and exposes
 * a TCP API using the MeshCore companion protocol.
 *
 * Frame format:
 * - Outbound (app -> daemon): '<' + 2-byte LE length + payload
 * - Inbound (daemon -> app): '>' + 2-byte LE length + payload
 */
@OptIn(ExperimentalForeignApi::class)
actual class MeshCoreSerial actual constructor() {

    companion object {
        const val CONNECT_RETRY_COUNT = 5
        const val INITIAL_RETRY_DELAY_MS = 1000
        const val MAX_RETRY_DELAY_MS = 16000
    }

    private var socketFd: Int = -1
    private var readJob: Job? = null
    private var scope: CoroutineScope? = null

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    actual val incoming: Flow<ByteArray> = _incoming.asSharedFlow()

    actual val isConnected: Boolean
        get() = socketFd >= 0

    actual var onDisconnect: (() -> Unit)? = null

    actual fun open(): Boolean {
        // Ensure meshcore service is running before connecting
        if (!ensureMeshcoreRunning()) {
            return false
        }

        return try {
            connectTcp()
        } catch (e: Exception) {
            println("❌ Failed to connect to meshcore-pi: ${e.message}")
            false
        }
    }

    private fun connectTcp(): Boolean = memScoped {
        println("📡 Connecting to meshcore-pi at ${MeshCoreConstants.DEFAULT_HOST}:${MeshCoreConstants.DEFAULT_PORT}...")

        val addr = alloc<sockaddr_in>()
        addr.sin_family = AF_INET.convert()
        addr.sin_port = htons(MeshCoreConstants.DEFAULT_PORT.toUShort())
        // 127.0.0.1 = 0x7F000001 in host byte order
        addr.sin_addr.s_addr = htonl(0x7F000001u)

        var lastError = 0
        var delayMs = INITIAL_RETRY_DELAY_MS

        for (attempt in 1..CONNECT_RETRY_COUNT) {
            socketFd = socket(AF_INET, SOCK_STREAM, 0)
            if (socketFd < 0) {
                println("❌ Failed to create socket: errno=$errno")
                return false
            }

            val result = connect(socketFd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
            if (result == 0) {
                // Success - configure socket options
                val flag = alloc<IntVar> { value = 1 }
                if (setsockopt(socketFd, IPPROTO_TCP, TCP_NODELAY, flag.ptr, sizeOf<IntVar>().convert()) < 0) {
                    println("⚠️ Failed to set TCP_NODELAY: errno=$errno (continuing anyway)")
                }

                val timeout = alloc<timeval>()
                timeout.tv_sec = 1
                timeout.tv_usec = 0
                if (setsockopt(socketFd, SOL_SOCKET, SO_RCVTIMEO, timeout.ptr, sizeOf<timeval>().convert()) < 0) {
                    println("⚠️ Failed to set SO_RCVTIMEO: errno=$errno (continuing anyway)")
                }

                println("✅ TCP connected to meshcore-pi (fd=$socketFd)")

                scope?.cancel()
                scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
                startReading()

                return true
            }

            lastError = errno
            close(socketFd)
            socketFd = -1

            // Only retry on ECONNREFUSED (111) - port not open yet
            if (lastError != 111) {
                println("❌ Failed to connect: errno=$lastError")
                return false
            }

            if (attempt < CONNECT_RETRY_COUNT) {
                println("⏳ Waiting for meshcore-pi port (attempt $attempt/$CONNECT_RETRY_COUNT, retry in ${delayMs}ms)...")
                usleep((delayMs * 1000).toUInt())
                delayMs = minOf(delayMs * 2, MAX_RETRY_DELAY_MS)
            }
        }

        println("❌ Failed to connect after $CONNECT_RETRY_COUNT attempts: errno=$lastError")
        return false
    }

    /**
     * Ensure meshcore service is running.
     */
    private fun ensureMeshcoreRunning(): Boolean {
        if (!MeshCoreService.isInstalled()) {
            println("❌ meshcore service is not installed")
            println("   Deploy meshcore-pi to the device first")
            return false
        }

        return MeshCoreService.start()
    }

    actual fun close() {
        readJob?.cancel()
        readJob = null

        if (socketFd >= 0) {
            close(socketFd)
            socketFd = -1
        }

        scope?.cancel()
        scope = null

        // Stop meshcore service to free up radio for other protocols
        MeshCoreService.stop()
    }

    actual fun send(data: ByteArray): Boolean {
        if (socketFd < 0) return false

        return try {
            val framed = buildFrame(data)

            val hexBytes = framed.joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
            println("📤 MeshCoreSerial: Sending frame: $hexBytes")

            val written = write(socketFd, framed.refTo(0), framed.size.convert())
            if (written < 0) {
                val err = errno
                println("❌ Error writing to meshcore-pi: errno=$err")
                if (err == 32) { // EPIPE - broken pipe
                    println("⚠️ MeshCoreSerial: Broken pipe, connection lost")
                    if (socketFd >= 0) {
                        close(socketFd)
                        socketFd = -1
                    }
                    onDisconnect?.invoke()
                }
                false
            } else {
                println("📤 MeshCoreSerial: Sent ${framed.size} bytes (payload=${data.size})")
                true
            }
        } catch (e: Exception) {
            println("❌ Error sending to meshcore-pi: ${e.message}")
            false
        }
    }

    /**
     * Start reading from the TCP socket.
     */
    private fun startReading() {
        readJob = scope?.launch {
            println("📖 MeshCoreSerial: Read coroutine started, fd=$socketFd")
            var state: ReadState = ReadState.WAIT_START
            val frameBuffer = mutableListOf<Byte>()
            var framesReceived = 0
            val buffer = ByteArray(256)

            while (isActive && isConnected) {
                try {
                    val bytesRead = read(socketFd, buffer.refTo(0), buffer.size.convert())

                    if (bytesRead < 0) {
                        val err = errno
                        if (err == EAGAIN || err == EWOULDBLOCK) {
                            continue // Timeout, retry
                        }
                        println("❌ Error reading from meshcore-pi: errno=$err")
                        if (socketFd >= 0) {
                            close(socketFd)
                            socketFd = -1
                        }
                        onDisconnect?.invoke()
                        break
                    } else if (bytesRead == 0L) {
                        println("📖 MeshCoreSerial: Connection closed by peer")
                        if (socketFd >= 0) {
                            close(socketFd)
                            socketFd = -1
                        }
                        onDisconnect?.invoke()
                        break
                    }

                    val hexBytes = (0 until bytesRead.toInt())
                        .joinToString(" ") { (buffer[it].toInt() and 0xFF).toString(16).padStart(2, '0') }
                    println("📥 MeshCoreSerial: Received ${bytesRead} bytes: $hexBytes")

                    // Process bytes through state machine
                    for (i in 0 until bytesRead.toInt()) {
                        val b = buffer[i]

                        when (state) {
                            ReadState.WAIT_START -> {
                                if (b == MeshCoreConstants.FRAME_START_INBOUND) {
                                    frameBuffer.clear()
                                    state = ReadState.READ_LEN_LOW
                                }
                            }

                            ReadState.READ_LEN_LOW -> {
                                state = ReadState.READ_LEN_HIGH(b.toInt() and 0xFF)
                            }

                            is ReadState.READ_LEN_HIGH -> {
                                val lenState = state as ReadState.READ_LEN_HIGH
                                // Little-endian: low byte first, then high byte
                                val length = lenState.lowByte or ((b.toInt() and 0xFF) shl 8)
                                state = if (length in 1..4095) {
                                    ReadState.READ_PAYLOAD(length, 0)
                                } else {
                                    println("⚠️ Invalid frame length: $length")
                                    ReadState.WAIT_START
                                }
                            }

                            is ReadState.READ_PAYLOAD -> {
                                frameBuffer.add(b)
                                val readPayload = state as ReadState.READ_PAYLOAD
                                val newRead = readPayload.bytesRead + 1

                                if (newRead >= readPayload.length) {
                                    val payload = frameBuffer.toByteArray()
                                    frameBuffer.clear()
                                    state = ReadState.WAIT_START
                                    framesReceived++

                                    try {
                                        _incoming.emit(payload)
                                    } catch (e: Exception) {
                                        println("❌ Error emitting payload: ${e.message}")
                                    }
                                } else {
                                    state = ReadState.READ_PAYLOAD(readPayload.length, newRead)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        println("❌ Error reading from meshcore-pi: ${e.message}")
                        if (socketFd >= 0) {
                            close(socketFd)
                            socketFd = -1
                        }
                        onDisconnect?.invoke()
                    }
                    break
                }
            }
            println("📖 MeshCoreSerial: Read loop exited (frames=$framesReceived)")
        }
    }

    /**
     * Build a framed packet for sending to meshcore-pi.
     *
     * Format: '<' + 2-byte LE length + payload
     */
    private fun buildFrame(data: ByteArray): ByteArray {
        val frame = ByteArray(3 + data.size)

        frame[0] = MeshCoreConstants.FRAME_START_OUTBOUND
        // Little-endian length
        frame[1] = (data.size and 0xFF).toByte()
        frame[2] = ((data.size shr 8) and 0xFF).toByte()
        data.copyInto(frame, 3)

        return frame
    }

    /**
     * State machine for reading framed packets.
     */
    private sealed class ReadState {
        data object WAIT_START : ReadState()
        data object READ_LEN_LOW : ReadState()
        data class READ_LEN_HIGH(val lowByte: Int) : ReadState()
        data class READ_PAYLOAD(val length: Int, val bytesRead: Int) : ReadState()
    }
}
