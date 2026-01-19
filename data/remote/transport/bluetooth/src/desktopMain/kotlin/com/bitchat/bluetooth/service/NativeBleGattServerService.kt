package com.bitchat.bluetooth.service

import com.bitchat.bluetooth.bridge.NativeBleBridge
import java.io.ByteArrayOutputStream

private const val BITCHAT_SERVICE_UUID = "F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C"

class NativeBleGattServerService(
    private val fallback: GattServerService,
    private val nativeAvailable: Boolean,
) : GattServerService {
    private var delegate: GattServerDelegate? = null
    private val reassemblyBuffers = mutableMapOf<String, ReassemblyBuffer>()

    override suspend fun startAdvertising() {
        if (nativeAvailable) {
            NativeBleBridge.startAdvertising(BITCHAT_SERVICE_UUID, "Bitchat")
            println("NativeBleGattServerService.startAdvertising: native bridge")
        } else {
            fallback.startAdvertising()
        }
    }

    override suspend fun stopAdvertising() {
        if (nativeAvailable) {
            NativeBleBridge.stopAdvertising()
            println("NativeBleGattServerService.stopAdvertising: native bridge")
        } else {
            fallback.stopAdvertising()
        }
    }

    override suspend fun onCharacteristicWriteRequest(data: ByteArray, deviceAddress: String) {
        if (nativeAvailable) {
            println("NativeBleGattServerService.onCharacteristicWriteRequest: native bridge stub ($deviceAddress, size=${data.size})")
        } else {
            fallback.onCharacteristicWriteRequest(data, deviceAddress)
        }
    }

    override suspend fun notifyCharacteristic(deviceAddress: String, data: ByteArray): Boolean {
        return if (nativeAvailable) {
            val ok = NativeBleBridge.notify(deviceAddress, data)
            println("NativeBleGattServerService.notifyCharacteristic: native bridge ${if (ok) "ok" else "failed"} (device=$deviceAddress, size=${data.size})")
            ok
        } else {
            fallback.notifyCharacteristic(deviceAddress, data)
        }
    }

    override fun setDelegate(delegate: GattServerDelegate) {
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
                    println("NativeBleGattServerService: Invalid START chunk from $deviceAddress - too short (${value.size} bytes)")
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
                println("NativeBleGattServerService: Started receiving chunked data from $deviceAddress, expecting $expected bytes")
            }

            CHUNK_CONTINUE, CHUNK_END -> {
                val buffer = reassemblyBuffers[deviceAddress]
                if (buffer == null) {
                    println("NativeBleGattServerService: Received chunk without START from $deviceAddress (${value.size} bytes)")
                    return
                }
                val payload = value.copyOfRange(1, value.size)
                buffer.data.write(payload)
                if (value[0] == CHUNK_END) {
                    val completeData = buffer.data.toByteArray()
                    reassemblyBuffers.remove(deviceAddress)
                    if (completeData.size != buffer.expectedSize) {
                        println("NativeBleGattServerService: Completed chunked transfer from $deviceAddress but size mismatch: expected ${buffer.expectedSize}, got ${completeData.size}")
                    } else {
                        println("NativeBleGattServerService: Completed chunked transfer from $deviceAddress: ${completeData.size} bytes")
                    }
                    delegate?.onDataReceived(completeData, deviceAddress)
                }
            }

            else -> delegate?.onDataReceived(value, deviceAddress)
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
