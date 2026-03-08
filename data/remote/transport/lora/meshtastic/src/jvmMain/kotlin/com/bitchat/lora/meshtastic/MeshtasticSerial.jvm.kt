package com.bitchat.lora.meshtastic

import com.fazecast.jSerialComm.SerialPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * JVM implementation of Meshtastic serial using jSerialComm.
 *
 * Handles the Meshtastic USB serial protocol:
 * - Start bytes: 0x94 0xC3
 * - Varint-encoded length
 * - Protobuf payload
 */
actual class MeshtasticSerial {

    private var port: SerialPort? = null
    private var readJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    actual val incoming: Flow<ByteArray> = _incoming.asSharedFlow()

    actual val isConnected: Boolean get() = port?.isOpen == true

    /**
     * Callback invoked when connection is lost unexpectedly.
     * Used by MeshtasticProtocol to trigger reconnection.
     */
    actual var onDisconnect: (() -> Unit)? = null

    actual fun open(): Boolean {
        // Find Meshtastic device
        val meshtasticPort = findMeshtasticPort()
        if (meshtasticPort == null) {
            println("❌ No Meshtastic device found")
            return false
        }

        println("📡 Found Meshtastic device: ${meshtasticPort.descriptivePortName}")

        port = meshtasticPort
        meshtasticPort.baudRate = 115200
        meshtasticPort.setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING, 100, 0)

        if (!meshtasticPort.openPort()) {
            println("❌ Failed to open port")
            port = null
            return false
        }

        println("✅ Meshtastic serial port opened")

        // Start reading
        startReading()
        return true
    }

    actual fun close() {
        readJob?.cancel()
        readJob = null
        port?.closePort()
        port = null
        scope.cancel()
    }

    actual fun send(data: ByteArray): Boolean {
        val currentPort = port ?: return false
        if (!currentPort.isOpen) return false

        // Build framed packet
        val framed = buildFrame(data)

        val written = currentPort.writeBytes(framed, framed.size)
        return written == framed.size
    }

    /**
     * Find a Meshtastic device by VID/PID.
     */
    private fun findMeshtasticPort(): SerialPort? {
        val ports = SerialPort.getCommPorts()

        for (port in ports) {
            val desc = port.descriptivePortName.lowercase()
            val portDesc = port.portDescription.lowercase()

            // Common Meshtastic device identifiers
            if (desc.contains("meshtastic") ||
                desc.contains("t-echo") ||
                desc.contains("t-beam") ||
                desc.contains("heltec") ||
                desc.contains("lilygo") ||
                portDesc.contains("cp210") ||  // Silicon Labs CP210x (common on ESP32)
                portDesc.contains("ch340") ||  // CH340 (common on budget boards)
                portDesc.contains("ch9102")    // CH9102 (newer alternative)
            ) {
                return port
            }
        }

        // Fallback: try first available USB serial
        return ports.firstOrNull { port ->
            port.descriptivePortName.lowercase().let {
                it.contains("usb") || it.contains("serial")
            }
        }
    }

    /**
     * Start reading from the serial port.
     */
    private fun startReading() {
        readJob = scope.launch {
            val buffer = ByteArray(1024)
            val frameBuffer = mutableListOf<Byte>()
            var state: ReadState = ReadState.WAIT_START_1

            while (isActive) {
                val currentPort = port ?: run {
                    println("⚠️ MeshtasticSerial: Port became null, connection lost")
                    onDisconnect?.invoke()
                    break
                }
                if (!currentPort.isOpen) {
                    println("⚠️ MeshtasticSerial: Port closed, connection lost")
                    onDisconnect?.invoke()
                    break
                }

                val bytesRead = try {
                    currentPort.readBytes(buffer, buffer.size)
                } catch (e: Exception) {
                    if (isActive) {
                        println("⚠️ Read error: ${e.message}")
                        port = null
                        onDisconnect?.invoke()
                    }
                    break
                }
                if (bytesRead <= 0) {
                    delay(10)
                    continue
                }

                for (i in 0 until bytesRead) {
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
                                ReadState.READ_VARINT
                            } else if (b == MeshtasticSerialConstants.START_BYTE_1.toByte()) {
                                // Might be start of new frame
                                ReadState.WAIT_START_2
                            } else {
                                ReadState.WAIT_START_1
                            }
                        }

                        ReadState.READ_VARINT -> {
                            frameBuffer.add(b)

                            // Check if varint is complete (MSB clear)
                            if ((b.toInt() and 0x80) == 0) {
                                val length = decodeVarint(frameBuffer.toByteArray())
                                frameBuffer.clear()
                                if (length > 0 && length < 1024) {
                                    state = ReadState.READ_PAYLOAD(length, 0)
                                } else {
                                    println("⚠️ Invalid frame length: $length")
                                    state = ReadState.WAIT_START_1
                                }
                            } else if (frameBuffer.size > 4) {
                                // Varint too long
                                println("⚠️ Varint too long")
                                frameBuffer.clear()
                                state = ReadState.WAIT_START_1
                            }
                        }

                        is ReadState.READ_PAYLOAD -> {
                            frameBuffer.add(b)
                            val readPayload = state as ReadState.READ_PAYLOAD
                            val newRead = readPayload.bytesRead + 1

                            if (newRead >= readPayload.length) {
                                // Frame complete
                                val payload = frameBuffer.toByteArray()
                                frameBuffer.clear()
                                state = ReadState.WAIT_START_1

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
            }
        }
    }

    /**
     * Build a framed packet with start bytes and varint length.
     */
    private fun buildFrame(data: ByteArray): ByteArray {
        val varint = encodeVarint(data.size)
        val frame = ByteArray(2 + varint.size + data.size)

        frame[0] = MeshtasticSerialConstants.START_BYTE_1.toByte()
        frame[1] = MeshtasticSerialConstants.START_BYTE_2.toByte()
        varint.copyInto(frame, 2)
        data.copyInto(frame, 2 + varint.size)

        return frame
    }

    /**
     * Encode an integer as a varint.
     */
    private fun encodeVarint(value: Int): ByteArray {
        val result = mutableListOf<Byte>()
        var v = value

        while (v >= 0x80) {
            result.add(((v and 0x7F) or 0x80).toByte())
            v = v ushr 7
        }
        result.add(v.toByte())

        return result.toByteArray()
    }

    /**
     * Decode a varint to an integer.
     */
    private fun decodeVarint(bytes: ByteArray): Int {
        var result = 0
        var shift = 0

        for (b in bytes) {
            result = result or ((b.toInt() and 0x7F) shl shift)
            if ((b.toInt() and 0x80) == 0) break
            shift += 7
        }

        return result
    }

    /**
     * State machine for reading framed packets.
     */
    private sealed class ReadState {
        data object WAIT_START_1 : ReadState()
        data object WAIT_START_2 : ReadState()
        data object READ_VARINT : ReadState()
        data class READ_PAYLOAD(val length: Int, val bytesRead: Int) : ReadState()
    }

}
