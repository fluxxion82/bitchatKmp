package com.bitchat.bluetooth.service

import com.bitchat.bluetooth.bridge.NativeBleBridge
import com.bitchat.bluetooth.protocol.ChunkReassembler

class NativeBleGattClientService(
    private val fallback: GattClientService,
    private val nativeAvailable: Boolean,
) : GattClientService {
    private var delegate: GattClientDelegate? = null

    private val reassembler = ChunkReassembler(log = ::println)

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

        val frame = reassembler.receive(deviceAddress, value) ?: return
        if (frame !== value) println("NativeBleGattClientService: Completed chunked transfer from $deviceAddress: ${frame.size} bytes")
        delegate?.onCharacteristicRead(deviceAddress, frame)
    }

    companion object {
        private val CHUNK_START: Byte = 0xFC.toByte()     // 252
        private val CHUNK_CONTINUE: Byte = 0xFD.toByte() // 253
        private val CHUNK_END: Byte = 0xFE.toByte()       // 254
    }
}
