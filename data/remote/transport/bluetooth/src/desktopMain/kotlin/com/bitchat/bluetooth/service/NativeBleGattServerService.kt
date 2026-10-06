package com.bitchat.bluetooth.service

import com.bitchat.bluetooth.bridge.NativeBleBridge
import com.bitchat.bluetooth.protocol.ChunkReassembler

private const val BITCHAT_SERVICE_UUID = "F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C"

class NativeBleGattServerService(
    private val fallback: GattServerService,
    private val nativeAvailable: Boolean,
) : GattServerService {
    private var delegate: GattServerDelegate? = null
    private val reassembler = ChunkReassembler(log = ::println)

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

        val frame = reassembler.receive(deviceAddress, value) ?: return
        if (frame !== value) println("NativeBleGattServerService: Completed chunked transfer from $deviceAddress: ${frame.size} bytes")
        delegate?.onDataReceived(frame, deviceAddress)
    }

    companion object {
        private val CHUNK_START: Byte = 0xFC.toByte()     // 252
        private val CHUNK_CONTINUE: Byte = 0xFD.toByte() // 253
        private val CHUNK_END: Byte = 0xFE.toByte()       // 254
    }
}
