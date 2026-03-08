package com.bitchat.lora.meshcore

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * JVM implementation of MeshCore serial using Java TCP sockets.
 *
 * Connects to meshcore-pi daemon on a remote host (e.g., Orange Pi).
 * Useful for desktop development/testing with remote hardware.
 */
actual class MeshCoreSerial actual constructor() {

    private var socket: Socket? = null
    private var readJob: Job? = null
    private var scope: CoroutineScope? = null

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    actual val incoming: Flow<ByteArray> = _incoming.asSharedFlow()

    actual val isConnected: Boolean
        get() = socket?.isConnected == true && socket?.isClosed == false

    actual var onDisconnect: (() -> Unit)? = null

    // For JVM, allow connecting to remote host
    var host: String = MeshCoreConstants.DEFAULT_HOST
    var port: Int = MeshCoreConstants.DEFAULT_PORT

    actual fun open(): Boolean {
        return try {
            println("📡 Connecting to meshcore-pi at $host:$port...")

            val newSocket = Socket()
            newSocket.connect(InetSocketAddress(host, port), 5000)
            newSocket.tcpNoDelay = true
            newSocket.soTimeout = 1000

            socket = newSocket

            scope?.cancel()
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            startReading()

            println("✅ Connected to meshcore-pi")
            true
        } catch (e: Exception) {
            println("❌ Failed to connect to meshcore-pi: ${e.message}")
            false
        }
    }

    actual fun close() {
        readJob?.cancel()
        readJob = null

        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null

        scope?.cancel()
        scope = null
    }

    actual fun send(data: ByteArray): Boolean {
        val sock = socket ?: return false
        if (!isConnected) return false

        return try {
            val frame = buildFrame(data)

            val hexBytes = frame.joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
            println("📤 MeshCoreSerial: Sending frame: $hexBytes")

            sock.getOutputStream().write(frame)
            sock.getOutputStream().flush()

            println("📤 MeshCoreSerial: Sent ${frame.size} bytes (payload=${data.size})")
            true
        } catch (e: IOException) {
            println("❌ Error sending to meshcore-pi: ${e.message}")
            close()
            onDisconnect?.invoke()
            false
        }
    }

    private fun startReading() {
        readJob = scope?.launch {
            println("📖 MeshCoreSerial: Read thread started")
            var state: ReadState = ReadState.WAIT_START
            val frameBuffer = mutableListOf<Byte>()
            var framesReceived = 0

            val sock = socket ?: return@launch
            val input = sock.getInputStream()

            while (isActive && isConnected) {
                try {
                    val b = input.read()
                    if (b < 0) {
                        println("📖 MeshCoreSerial: Connection closed by peer")
                        close()
                        onDisconnect?.invoke()
                        break
                    }

                    when (state) {
                        ReadState.WAIT_START -> {
                            if (b.toByte() == MeshCoreConstants.FRAME_START_INBOUND) {
                                frameBuffer.clear()
                                state = ReadState.READ_LEN_LOW
                            }
                        }

                        ReadState.READ_LEN_LOW -> {
                            state = ReadState.READ_LEN_HIGH(b and 0xFF)
                        }

                        is ReadState.READ_LEN_HIGH -> {
                            val lenState = state as ReadState.READ_LEN_HIGH
                            val length = lenState.lowByte or ((b and 0xFF) shl 8)
                            state = if (length in 1..4095) {
                                ReadState.READ_PAYLOAD(length, 0)
                            } else {
                                ReadState.WAIT_START
                            }
                        }

                        is ReadState.READ_PAYLOAD -> {
                            frameBuffer.add(b.toByte())
                            val readPayload = state as ReadState.READ_PAYLOAD
                            val newRead = readPayload.bytesRead + 1

                            if (newRead >= readPayload.length) {
                                val payload = frameBuffer.toByteArray()
                                frameBuffer.clear()
                                state = ReadState.WAIT_START
                                framesReceived++

                                _incoming.emit(payload)
                            } else {
                                state = ReadState.READ_PAYLOAD(readPayload.length, newRead)
                            }
                        }
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    // Timeout is expected, continue reading
                    continue
                } catch (e: Exception) {
                    if (isActive) {
                        println("❌ Error reading from meshcore-pi: ${e.message}")
                        close()
                        onDisconnect?.invoke()
                    }
                    break
                }
            }
            println("📖 MeshCoreSerial: Read loop exited (frames=$framesReceived)")
        }
    }

    private fun buildFrame(data: ByteArray): ByteArray {
        val frame = ByteArray(3 + data.size)
        frame[0] = MeshCoreConstants.FRAME_START_OUTBOUND
        frame[1] = (data.size and 0xFF).toByte()
        frame[2] = ((data.size shr 8) and 0xFF).toByte()
        data.copyInto(frame, 3)
        return frame
    }

    private sealed class ReadState {
        data object WAIT_START : ReadState()
        data object READ_LEN_LOW : ReadState()
        data class READ_LEN_HIGH(val lowByte: Int) : ReadState()
        data class READ_PAYLOAD(val length: Int, val bytesRead: Int) : ReadState()
    }
}
