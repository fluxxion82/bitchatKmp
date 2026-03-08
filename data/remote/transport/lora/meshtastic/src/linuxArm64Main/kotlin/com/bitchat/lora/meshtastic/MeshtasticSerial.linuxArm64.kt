package com.bitchat.lora.meshtastic

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
 * Linux ARM64 implementation of Meshtastic serial using POSIX TCP sockets.
 *
 * Connects to meshtasticd daemon running on localhost:4403.
 * meshtasticd controls the SPI LoRa radio and exposes a TCP API
 * using the same protocol framing as USB serial:
 * - Start bytes: 0x94 0xC3
 * - Varint-encoded length
 * - Protobuf payload
 *
 * Note: When using Meshtastic protocol, BitChat's direct SPI access
 * must be disabled since meshtasticd controls the radio.
 */
@OptIn(ExperimentalForeignApi::class)
actual class MeshtasticSerial {

    companion object {
        /** Default meshtasticd TCP port */
        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 4403

        /** Connection retry settings - meshtasticd may take time to open TCP port after starting */
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

    /**
     * Callback invoked when connection is lost unexpectedly.
     * Used by MeshtasticProtocol to trigger reconnection.
     */
    actual var onDisconnect: (() -> Unit)? = null

    actual fun open(): Boolean {
        // Ensure meshtasticd service is running before connecting
        if (!ensureMeshtasticdRunning()) {
            return false
        }

        return try {
            connectTcp()
        } catch (e: Exception) {
            println("❌ Failed to connect to meshtasticd: ${e.message}")
            false
        }
    }

    private fun connectTcp(): Boolean = memScoped {
        println("📡 Connecting to meshtasticd at $DEFAULT_HOST:$DEFAULT_PORT...")

        // Set up address (reused across retries)
        val addr = alloc<sockaddr_in>()
        addr.sin_family = AF_INET.convert()
        addr.sin_port = htons(DEFAULT_PORT.toUShort())
        // 127.0.0.1 = 0x7F000001 in host byte order, converted to network byte order
        addr.sin_addr.s_addr = htonl(0x7F000001u)

        // Retry connection with exponential backoff - meshtasticd may take time to open TCP port after starting
        var lastError = 0
        var delayMs = INITIAL_RETRY_DELAY_MS

        for (attempt in 1..CONNECT_RETRY_COUNT) {
            // Create socket
            socketFd = socket(AF_INET, SOCK_STREAM, 0)
            if (socketFd < 0) {
                println("❌ Failed to create socket: errno=$errno")
                return false
            }

            // Try to connect
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

                println("✅ TCP connected to meshtasticd (fd=$socketFd)")

                // Create fresh scope and start reading
                scope?.cancel()
                scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
                startReading()

                return true
            }

            // Connection failed
            lastError = errno
            close(socketFd)
            socketFd = -1

            // Only retry on ECONNREFUSED (111) - port not open yet
            if (lastError != 111) {
                println("❌ Failed to connect: errno=$lastError")
                return false
            }

            if (attempt < CONNECT_RETRY_COUNT) {
                println("⏳ Waiting for meshtasticd port (attempt $attempt/$CONNECT_RETRY_COUNT, retry in ${delayMs}ms)...")
                usleep((delayMs * 1000).toUInt())
                // Exponential backoff: 1s -> 2s -> 4s -> 8s -> 16s
                delayMs = minOf(delayMs * 2, MAX_RETRY_DELAY_MS)
            }
        }

        println("❌ Failed to connect after $CONNECT_RETRY_COUNT attempts: errno=$lastError")
        return false
    }

    /**
     * Ensure meshtasticd service is running.
     */
    private fun ensureMeshtasticdRunning(): Boolean {
        if (!MeshtasticdService.isInstalled()) {
            println("❌ meshtasticd is not installed")
            println("   Install with: sudo apt install meshtasticd")
            println("   Or follow: https://meshtastic.org/docs/hardware/devices/linux-native-hardware/")
            return false
        }

        return MeshtasticdService.start()
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
    }

    actual fun send(data: ByteArray): Boolean {
        if (socketFd < 0) return false

        return try {
            // Build framed packet
            val framed = buildFrame(data)

            // Log the exact bytes being sent
            val hexBytes = framed.joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
            println("📤 MeshtasticSerial: Sending frame: $hexBytes")

            val written = write(socketFd, framed.refTo(0), framed.size.convert())
            if (written < 0) {
                val err = errno
                println("❌ Error writing to meshtasticd: errno=$err")
                // Handle EPIPE (32) - broken pipe means connection is dead
                if (err == 32) {
                    println("⚠️ MeshtasticSerial: Broken pipe (EPIPE), connection lost")
                    if (socketFd >= 0) {
                        close(socketFd)
                        socketFd = -1
                    }
                    onDisconnect?.invoke()
                }
                false
            } else {
                println("📤 MeshtasticSerial: Sent ${framed.size} bytes (payload=${data.size}), written=$written")
                true
            }
        } catch (e: Exception) {
            println("❌ Error sending to meshtasticd: ${e.message}")
            false
        }
    }

    /**
     * Start reading from the TCP socket using blocking POSIX read() with SO_RCVTIMEO.
     *
     * This uses SO_RCVTIMEO (socket receive timeout) instead of poll() because
     * poll() has type conversion issues on linuxArm64 that cause it to always
     * return 0 even when data is available. Blocking read() with kernel-managed
     * timeout is more reliable.
     */
    private fun startReading() {
        println("📖 MeshtasticSerial: startReading() called, scope=$scope")
        readJob = scope?.launch {
            println("📖 MeshtasticSerial: Read coroutine started, fd=$socketFd")
            var state: ReadState = ReadState.WAIT_START_1
            val frameBuffer = mutableListOf<Byte>()
            var framesReceived = 0
            val buffer = ByteArray(256)
            var readCount = 0

            while (isActive && isConnected) {
                try {
                    readCount++
                    if (readCount <= 3 || readCount % 10 == 1) {
                        println("📖 MeshtasticSerial: calling read() (count=$readCount, fd=$socketFd, isConnected=$isConnected)")
                    }

                    // Blocking read with SO_RCVTIMEO timeout (set in connectTcp)
                    val bytesRead = read(socketFd, buffer.refTo(0), buffer.size.convert())

                    if (bytesRead < 0) {
                        val err = errno
                        // EAGAIN/EWOULDBLOCK = timeout expired, not an error - continue loop
                        if (err == EAGAIN || err == EWOULDBLOCK) {
                            if (readCount % 10 == 0) {
                                println("📖 MeshtasticSerial: read timeout (EAGAIN), continuing... (count=$readCount)")
                            }
                            continue
                        }
                        println("❌ Error reading from meshtasticd: errno=$err")
                        // Close and mark disconnected
                        if (socketFd >= 0) {
                            close(socketFd)
                            socketFd = -1
                        }
                        onDisconnect?.invoke()
                        break
                    } else if (bytesRead == 0L) {
                        println("📖 MeshtasticSerial: Connection closed by peer")
                        // Close and mark disconnected
                        if (socketFd >= 0) {
                            close(socketFd)
                            socketFd = -1
                        }
                        onDisconnect?.invoke()
                        break
                    }

                    // Log received bytes for debugging
                    val hexBytes = (0 until bytesRead.toInt())
                        .joinToString(" ") { (buffer[it].toInt() and 0xFF).toString(16).padStart(2, '0') }
                    println("📥 MeshtasticSerial: Received ${bytesRead} bytes: $hexBytes")

                    // Process all bytes read
                    for (i in 0 until bytesRead.toInt()) {
                        val b = buffer[i]

                        when (state) {
                            ReadState.WAIT_START_1 -> {
                                if (b == MeshtasticSerialConstants.START_BYTE_1.toByte()) {
                                    frameBuffer.clear()
                                    state = ReadState.WAIT_START_2
                                }
                            }

                            ReadState.WAIT_START_2 -> {
                                state = if (b == MeshtasticSerialConstants.START_BYTE_2.toByte()) {
                                    ReadState.READ_LEN_MSB
                                } else if (b == MeshtasticSerialConstants.START_BYTE_1.toByte()) {
                                    ReadState.WAIT_START_2
                                } else {
                                    ReadState.WAIT_START_1
                                }
                            }

                            ReadState.READ_LEN_MSB -> {
                                // First byte of 16-bit big-endian length
                                state = ReadState.READ_LEN_LSB(b.toInt() and 0xFF)
                            }

                            is ReadState.READ_LEN_LSB -> {
                                // Second byte of 16-bit big-endian length
                                val lenState = state as ReadState.READ_LEN_LSB
                                val length = (lenState.msb shl 8) or (b.toInt() and 0xFF)
                                state = if (length in 1..4095) {
                                    ReadState.READ_PAYLOAD(length, 0)
                                } else {
                                    ReadState.WAIT_START_1
                                }
                            }

                            is ReadState.READ_PAYLOAD -> {
                                frameBuffer.add(b)
                                val readPayload = state as ReadState.READ_PAYLOAD
                                val newRead = readPayload.bytesRead + 1

                                if (newRead >= readPayload.length) {
                                    val payload = frameBuffer.toByteArray()
                                    frameBuffer.clear()
                                    state = ReadState.WAIT_START_1
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
                        println("❌ Error reading from meshtasticd: ${e.message}")
                        // Close and mark disconnected
                        if (socketFd >= 0) {
                            close(socketFd)
                            socketFd = -1
                        }
                        onDisconnect?.invoke()
                    }
                    break
                }
            }
            println("📖 MeshtasticSerial: Read loop exited (frames=$framesReceived)")
        }
    }

    /**
     * Build a framed packet with start bytes and 16-bit big-endian length.
     *
     * Meshtastic TCP framing format:
     * - 0x94 0xC3 (start bytes)
     * - 16-bit big-endian length (MSB first)
     * - Protobuf payload
     */
    private fun buildFrame(data: ByteArray): ByteArray {
        val frame = ByteArray(4 + data.size)

        frame[0] = MeshtasticSerialConstants.START_BYTE_1.toByte()
        frame[1] = MeshtasticSerialConstants.START_BYTE_2.toByte()
        // 16-bit big-endian length
        frame[2] = ((data.size shr 8) and 0xFF).toByte()
        frame[3] = (data.size and 0xFF).toByte()
        data.copyInto(frame, 4)

        return frame
    }

    /**
     * State machine for reading framed packets.
     *
     * Meshtastic TCP uses 16-bit big-endian length after start bytes.
     */
    private sealed class ReadState {
        data object WAIT_START_1 : ReadState()
        data object WAIT_START_2 : ReadState()
        data object READ_LEN_MSB : ReadState()
        data class READ_LEN_LSB(val msb: Int) : ReadState()
        data class READ_PAYLOAD(val length: Int, val bytesRead: Int) : ReadState()
    }
}
