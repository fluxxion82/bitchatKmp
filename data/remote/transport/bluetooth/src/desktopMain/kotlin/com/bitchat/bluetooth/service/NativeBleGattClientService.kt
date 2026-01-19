package com.bitchat.bluetooth.service

import com.bitchat.bluetooth.bridge.NativeBleBridge
import java.io.ByteArrayOutputStream

class NativeBleGattClientService(
    private val fallback: GattClientService,
    private val nativeAvailable: Boolean,
) : GattClientService {
    private var delegate: GattClientDelegate? = null

    private val reassemblyBuffers = mutableMapOf<String, ReassemblyBuffer>()

    override suspend fun writeCharacteristic(deviceAddress: String, data: ByteArray): Boolean {
        return if (nativeAvailable) {
            val ok = NativeBleBridge.write(deviceAddress, data)
            println("NativeBleGattClientService.writeCharacteristic: native bridge ${if (ok) "ok" else "failed"} (device=$deviceAddress, size=${data.size})")
            ok
        } else {
            fallback.writeCharacteristic(deviceAddress, data)
        }
    }

    override suspend fun disconnect(deviceAddress: String) {
        if (nativeAvailable) {
            NativeBleBridge.disconnect(deviceAddress)
            println("NativeBleGattClientService.disconnect: native bridge (device=$deviceAddress)")
        } else {
            fallback.disconnect(deviceAddress)
        }
    }

    override suspend fun disconnectAll() {
        println("NativeBleGattClientService.disconnectAll: delegating to fallback")
        fallback.disconnectAll()
    }

    override fun setDelegate(delegate: GattClientDelegate) {
        this.delegate = delegate
        fallback.setDelegate(delegate)
        if (nativeAvailable) {
            NativeBleBridge.addDataListener { deviceId, bytes ->
                handleIncomingData(deviceId, bytes)
            }
        }
    }

    private fun handleIncomingData(deviceAddress: String, value: ByteArray) {
        if (value.isEmpty()) return

        when (value[0]) {
            CHUNK_START -> {
                if (value.size < 6) {
                    println("NativeBleGattClientService: Invalid START chunk from $deviceAddress - too short (${value.size} bytes)")
                    return
                }
                val expected = ((value[1].toInt() and 0xFF) shl 24) or
                        ((value[2].toInt() and 0xFF) shl 16) or
                        ((value[3].toInt() and 0xFF) shl 8) or
                        (value[4].toInt() and 0xFF)
                val payload = value.copyOfRange(5, value.size)
                val buffer = ReassemblyBuffer(expectedSize = expected)
                buffer.data.write(payload)
                reassemblyBuffers[deviceAddress] = buffer
                println("NativeBleGattClientService: Started receiving chunked data from $deviceAddress, expecting $expected bytes")
            }

            CHUNK_CONTINUE, CHUNK_END -> {
                val buffer = reassemblyBuffers[deviceAddress]
                if (buffer == null) {
                    println("NativeBleGattClientService: Received chunk without START from $deviceAddress (${value.size} bytes)")
                    return
                }
                val payload = value.copyOfRange(1, value.size)
                buffer.data.write(payload)
                if (value[0] == CHUNK_END) {
                    val completeData = buffer.data.toByteArray()
                    reassemblyBuffers.remove(deviceAddress)
                    if (completeData.size != buffer.expectedSize) {
                        println("NativeBleGattClientService: Completed chunked transfer from $deviceAddress but size mismatch: expected ${buffer.expectedSize}, got ${completeData.size}")
                    } else {
                        println("NativeBleGattClientService: Completed chunked transfer from $deviceAddress: ${completeData.size} bytes")
                    }
                    delegate?.onCharacteristicRead(deviceAddress, completeData)
                }
            }

            else -> delegate?.onCharacteristicRead(deviceAddress, value)
        }
    }

    private data class ReassemblyBuffer(
        var expectedSize: Int = 0,
        val data: ByteArrayOutputStream = ByteArrayOutputStream()
    )

    companion object {
        private val CHUNK_START: Byte = 0xFC.toByte()     // 252
        private val CHUNK_CONTINUE: Byte = 0xFD.toByte() // 253
        private val CHUNK_END: Byte = 0xFE.toByte()       // 254
    }
}
