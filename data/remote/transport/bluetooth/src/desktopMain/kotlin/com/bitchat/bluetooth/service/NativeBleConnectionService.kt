package com.bitchat.bluetooth.service

import com.bitchat.bluetooth.bridge.NativeBleBridge
import kotlinx.coroutines.delay

class NativeBleConnectionService(
    private val fallback: BluetoothConnectionService,
) : BluetoothConnectionService {
    private val nativeAvailable = NativeBleBridge.init()

    override suspend fun connectToDevice(deviceAddress: String) {
        if (nativeAvailable) {
            val ping = NativeBleBridge.ping()
            println("NativeBleConnectionService.connectToDevice: native bridge active (ping=$ping) device=$deviceAddress")
            NativeBleBridge.connect(deviceAddress)
        } else {
            fallback.connectToDevice(deviceAddress)
        }
    }

    override suspend fun confirmDevice() {
        if (nativeAvailable) {
            println("NativeBleConnectionService.confirmDevice: native bridge active")
        } else {
            fallback.confirmDevice()
        }
    }

    override suspend fun isDeviceConnecting(deviceAddress: String): Boolean {
        return if (nativeAvailable) {
            println("NativeBleConnectionService.isDeviceConnecting: native bridge active device=$deviceAddress")
            false
        } else {
            fallback.isDeviceConnecting(deviceAddress)
        }
    }

    override suspend fun disconnectDeviceByAddress(deviceAddress: String) {
        if (nativeAvailable) {
            println("NativeBleConnectionService.disconnectDeviceByAddress: native bridge active device=$deviceAddress")
        } else {
            fallback.disconnectDeviceByAddress(deviceAddress)
        }
    }

    override suspend fun clearConnections() {
        if (nativeAvailable) {
            println("NativeBleConnectionService.clearConnections: native bridge active")
        } else {
            fallback.clearConnections()
        }
    }

    override suspend fun broadcastPacket(packetData: ByteArray) {
        if (nativeAvailable) {
            println("NativeBleConnectionService.broadcastPacket: native bridge active size=${packetData.size}")
            if (packetData.size <= CHUNK_SIZE) {
                NativeBleBridge.broadcast(packetData)
            } else {
                broadcastChunked(packetData)
            }
        } else {
            fallback.broadcastPacket(packetData)
        }
    }

    private suspend fun broadcastChunked(data: ByteArray) {
        val totalSize = data.size
        val chunks = mutableListOf<ByteArray>()

        var offset = 0
        var chunkIndex = 0

        while (offset < totalSize) {
            val remaining = totalSize - offset
            val isFirst = chunkIndex == 0
            val headerSize = if (isFirst) 5 else 1
            val payloadSize = minOf(remaining, CHUNK_SIZE - headerSize)
            val isLast = offset + payloadSize >= totalSize

            val chunkType: Byte = when {
                isFirst -> CHUNK_START
                isLast -> CHUNK_END
                else -> CHUNK_CONTINUE
            }

            val chunk = if (isFirst) {
                ByteArray(1 + 4 + payloadSize).apply {
                    this[0] = chunkType
                    this[1] = ((totalSize shr 24) and 0xFF).toByte()
                    this[2] = ((totalSize shr 16) and 0xFF).toByte()
                    this[3] = ((totalSize shr 8) and 0xFF).toByte()
                    this[4] = (totalSize and 0xFF).toByte()
                    System.arraycopy(data, offset, this, 5, payloadSize)
                }
            } else {
                ByteArray(1 + payloadSize).apply {
                    this[0] = chunkType
                    System.arraycopy(data, offset, this, 1, payloadSize)
                }
            }

            chunks.add(chunk)
            offset += payloadSize
            chunkIndex++
        }

        println("NativeBleConnectionService: Chunking ${data.size} bytes into ${chunks.size} chunks")

        for ((index, chunk) in chunks.withIndex()) {
            val success = NativeBleBridge.broadcast(chunk)
            if (!success) {
                println("NativeBleConnectionService: Failed to broadcast chunk ${index + 1}/${chunks.size}")
                return
            }

            if (index < chunks.size - 1) {
                delay(CHUNK_DELAY_MS)
            }
        }

        println("NativeBleConnectionService: Successfully broadcast all ${chunks.size} chunks (${data.size} bytes)")
    }

    override fun hasRequiredPermissions(): Boolean =
        if (nativeAvailable) true else fallback.hasRequiredPermissions()

    override fun setConnectionEstablishedCallback(callback: ConnectionEstablishedCallback) {
        fallback.setConnectionEstablishedCallback(callback)
    }

    override fun setConnectionReadyCallback(callback: ConnectionReadyCallback) {
        fallback.setConnectionReadyCallback(callback)
    }

    companion object {
        // Chunking constants for large data transfers
        // BLE MTU is typically 512-517, leave room for headers
        private const val CHUNK_SIZE = 500
        private const val CHUNK_DELAY_MS = 25L  // Delay between chunks to prevent buffer overflow

        // Chunk type markers - must match Android's AndroidGattClientService
        private val CHUNK_START: Byte = 0xFC.toByte()     // 252
        private val CHUNK_CONTINUE: Byte = 0xFD.toByte()  // 253
        private val CHUNK_END: Byte = 0xFE.toByte()       // 254
    }
}
