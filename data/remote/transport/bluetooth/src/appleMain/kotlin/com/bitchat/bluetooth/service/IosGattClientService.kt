package com.bitchat.bluetooth.service

import com.bitchat.bluetooth.protocol.logDebug
import com.bitchat.bluetooth.protocol.logError
import com.bitchat.bluetooth.protocol.logInfo
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicWriteWithoutResponse
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBService
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSData
import platform.Foundation.NSUUID
import platform.Foundation.create

/**
 * iOS GATT Client Service using shared CBCentralManager
 */
class IosGattClientService(
    private val sharedCentralManager: IosSharedCentralManager
) : GattClientService, IosSharedCentralManager.GattDelegate {
    companion object {
        private const val SERVICE_UUID = "F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C"
        private const val CHARACTERISTIC_UUID = "A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5D"
        // Use high byte values to avoid conflict with packet types (0x01=ANNOUNCE, etc.)
        // 0xFC=-4, 0xFD=-3, 0xFE=-2 as signed bytes
        private const val CHUNK_START: Byte = -4     // 0xFC (252 unsigned)
        private const val CHUNK_CONTINUE: Byte = -3  // 0xFD (253 unsigned)
        private const val CHUNK_END: Byte = -2       // 0xFE (254 unsigned)
    }

    private data class DeviceConnection(
        val peripheral: CBPeripheral,
        var characteristic: CBCharacteristic? = null,
        var isReady: Boolean = false
    )

    private val connections = mutableMapOf<String, DeviceConnection>()
    private var delegate: GattClientDelegate? = null
    private var connectionDelegate: IosGattClientConnectionDelegate? = null
    private var readyCallback: ConnectionReadyCallback? = null
    private data class ReassemblyBuffer(var expectedSize: Int = 0, val data: MutableList<Byte> = mutableListOf())
    private val reassemblyBuffers = mutableMapOf<String, ReassemblyBuffer>()

    init {
        sharedCentralManager.registerGattDelegate(this)
    }

    override suspend fun writeCharacteristic(deviceAddress: String, data: ByteArray): Boolean {
        logDebug("GATT_CLIENT", "Write ${data.size}B to ${deviceAddress.take(8)}")
        val connection = connections[deviceAddress]
        if (connection == null) {
            logError("GATT_CLIENT", "No connection for ${deviceAddress.take(8)}")
            connectToDevice(deviceAddress)
            return false
        }

        val characteristic = connection.characteristic
        if (!connection.isReady || characteristic == null) {
            logError("GATT_CLIENT", "Not ready (isReady=${connection.isReady}, char=${characteristic != null})")
            return false
        }

        // Use chunking for large payloads (BLE MTU is typically ~512 bytes)
        val maxChunkSize = 499 // Leave room for chunk header
        if (data.size > maxChunkSize) {
            return writeChunked(connection, deviceAddress, data, maxChunkSize)
        }

        val payload = data.toNSData()
        connection.peripheral.writeValue(payload, characteristic, CBCharacteristicWriteWithoutResponse)
        delegate?.onWriteSuccess(deviceAddress)
        return true
    }

    private suspend fun writeChunked(
        connection: DeviceConnection,
        deviceAddress: String,
        data: ByteArray,
        maxPayloadSize: Int
    ): Boolean {
        val characteristic = connection.characteristic ?: return false
        val totalSize = data.size
        var offset = 0
        var chunkNumber = 0
        val totalChunks = (totalSize + maxPayloadSize - 1) / maxPayloadSize

        logInfo("GATT_CLIENT", "Chunking $totalSize bytes into $totalChunks chunks for ${deviceAddress.take(8)}")

        while (offset < totalSize) {
            val remaining = totalSize - offset
            val payloadSize = minOf(remaining, maxPayloadSize)
            val isFirst = offset == 0
            val isLast = offset + payloadSize >= totalSize

            // Build chunk with header
            val chunkType: Byte = when {
                isFirst -> CHUNK_START
                isLast -> CHUNK_END
                else -> CHUNK_CONTINUE
            }

            val chunk: ByteArray = if (isFirst) {
                // First chunk: type (1 byte) + total size (4 bytes big-endian) + payload
                ByteArray(5 + payloadSize).also { arr ->
                    arr[0] = chunkType
                    arr[1] = ((totalSize shr 24) and 0xFF).toByte()
                    arr[2] = ((totalSize shr 16) and 0xFF).toByte()
                    arr[3] = ((totalSize shr 8) and 0xFF).toByte()
                    arr[4] = (totalSize and 0xFF).toByte()
                    data.copyInto(arr, 5, offset, offset + payloadSize)
                }
            } else {
                // Continuation/End chunk: type (1 byte) + payload
                ByteArray(1 + payloadSize).also { arr ->
                    arr[0] = chunkType
                    data.copyInto(arr, 1, offset, offset + payloadSize)
                }
            }

            // Write chunk
            val payload = chunk.toNSData()
            connection.peripheral.writeValue(payload, characteristic, CBCharacteristicWriteWithoutResponse)

            chunkNumber++
            logDebug("GATT_CLIENT", "Wrote chunk $chunkNumber/$totalChunks (${chunk.size} bytes) to ${deviceAddress.take(8)}")

            offset += payloadSize

            // Small delay between chunks to prevent overwhelming the BLE stack
            if (!isLast) {
                kotlinx.coroutines.delay(25)
            }
        }

        logInfo("GATT_CLIENT", "Successfully wrote all $totalChunks chunks ($totalSize bytes) to ${deviceAddress.take(8)}")
        delegate?.onWriteSuccess(deviceAddress)
        return true
    }

    override suspend fun disconnect(deviceAddress: String) {
        connections[deviceAddress]?.let { connection ->
            sharedCentralManager.disconnectPeripheral(connection.peripheral)
        }
    }

    override suspend fun disconnectAll() {
        connections.values.forEach { connection ->
            sharedCentralManager.disconnectPeripheral(connection.peripheral)
        }
    }

    override fun setDelegate(delegate: GattClientDelegate) {
        this.delegate = delegate
    }

    fun setReadyCallback(callback: ConnectionReadyCallback?) {
        readyCallback = callback
    }

    fun setConnectionDelegate(delegate: IosGattClientConnectionDelegate) {
        this.connectionDelegate = delegate
    }

    fun registerDiscoveredPeripheral(peripheral: CBPeripheral) {
        val deviceAddress = peripheral.identifier.UUIDString
        if (connections.containsKey(deviceAddress)) {
            return
        }
        connections[deviceAddress] = DeviceConnection(peripheral = peripheral)
    }

    fun connectToDevice(deviceAddress: String) {
        logInfo("GATT_CLIENT", "Connecting to ${deviceAddress.take(8)}")
        if (sharedCentralManager.getState() != CBManagerStatePoweredOn) {
            logError("GATT_CLIENT", "BT not powered on (state: ${sharedCentralManager.getState()})")
            connectionDelegate?.onConnectionFailure(deviceAddress, "Bluetooth not powered on")
            return
        }

        val connection = connections[deviceAddress]
        val peripheral = connection?.peripheral ?: run {
            logDebug("GATT_CLIENT", "Retrieving peripheral ${deviceAddress.take(8)}")
            val uuid = NSUUID(deviceAddress)
            val retrieved = sharedCentralManager.retrievePeripherals(listOf(uuid))
            val restored = retrieved.firstOrNull() as? CBPeripheral
            if (restored != null) {
                connections[deviceAddress] = DeviceConnection(peripheral = restored)
                restored
            } else {
                logError("GATT_CLIENT", "Peripheral ${deviceAddress.take(8)} not found")
                connectionDelegate?.onConnectionFailure(deviceAddress, "Peripheral not found")
                return
            }
        }

        sharedCentralManager.connectPeripheral(peripheral, null)
    }

    private fun handleConnected(peripheral: CBPeripheral) {
        val deviceAddress = peripheral.identifier.UUIDString
        logInfo("GATT_CLIENT", "Connected ${deviceAddress.take(8)}, discovering services")
        peripheral.discoverServices(listOf(CBUUID.UUIDWithString(SERVICE_UUID)))
    }

    private fun handleDisconnected(peripheral: CBPeripheral, reason: String?) {
        val deviceAddress = peripheral.identifier.UUIDString
        connections.remove(deviceAddress)
        connectionDelegate?.onConnectionFailure(deviceAddress, reason ?: "Disconnected")
    }

    private fun handleCharacteristicReady(peripheral: CBPeripheral, characteristic: CBCharacteristic) {
        val deviceAddress = peripheral.identifier.UUIDString
        logInfo("GATT_CLIENT", "Characteristic ready for ${deviceAddress.take(8)}")
        val connection = connections[deviceAddress]
        if (connection != null) {
            connection.characteristic = characteristic
            connection.isReady = true
        } else {
            connections[deviceAddress] = DeviceConnection(peripheral, characteristic, true)
        }
        logDebug("GATT_CLIENT", "Total ready: ${connections.count { it.value.isReady }}")
        readyCallback?.onConnectionReady(deviceAddress)
        connectionDelegate?.onConnectionSuccess(deviceAddress)
    }

    override fun onConnected(peripheral: CBPeripheral) {
        val deviceAddress = peripheral.identifier.UUIDString
        logInfo("GATT_CLIENT", "Connected: ${deviceAddress.take(8)}")
        handleConnected(peripheral)
    }

    override fun onDisconnected(peripheral: CBPeripheral, error: String?) {
        handleDisconnected(peripheral, error)
    }

    override fun onServicesDiscovered(peripheral: CBPeripheral, error: String?) {
        if (error != null) {
            logError("GATT_CLIENT", "Service discovery error: $error")
            handleDisconnected(peripheral, error)
            return
        }

        val services = peripheral.services
        logDebug("GATT_CLIENT", "Services found: ${services?.size ?: 0}")
        if (services == null) {
            logError("GATT_CLIENT", "No services found")
            return
        }

        services.forEach { service ->
            val cbService = service as? CBService ?: return@forEach
            if (cbService.UUID == CBUUID.UUIDWithString(SERVICE_UUID)) {
                peripheral.discoverCharacteristics(
                    listOf(CBUUID.UUIDWithString(CHARACTERISTIC_UUID)),
                    cbService
                )
            }
        }
    }

    override fun onCharacteristicsDiscovered(peripheral: CBPeripheral, service: CBService, error: String?) {
        if (error != null) {
            logError("GATT_CLIENT", "Characteristic discovery error: $error")
            handleDisconnected(peripheral, error)
            return
        }

        val characteristics = service.characteristics
        logDebug("GATT_CLIENT", "Characteristics found: ${characteristics?.size ?: 0}")
        if (characteristics == null) {
            logError("GATT_CLIENT", "No characteristics found")
            return
        }

        val target = characteristics.firstOrNull {
            (it as? CBCharacteristic)?.UUID == CBUUID.UUIDWithString(CHARACTERISTIC_UUID)
        } as? CBCharacteristic

        if (target != null) {
            peripheral.setNotifyValue(true, target)
            handleCharacteristicReady(peripheral, target)
        } else {
            logError("GATT_CLIENT", "Target characteristic not found")
        }
    }

    override fun onCharacteristicValueUpdated(peripheral: CBPeripheral, characteristic: CBCharacteristic, error: String?) {
        if (error != null) {
            return
        }
        val value = characteristic.value?.toByteArray() ?: return
        val deviceAddress = peripheral.identifier.UUIDString
        handleIncomingData(deviceAddress, value)
    }

    override fun onMtuChanged(peripheral: CBPeripheral, mtu: Int, error: String?) {
        // MTU changes not critical for this implementation
    }

    fun getReadyDeviceAddresses(): List<String> {
        return connections.filterValues { it.isReady && it.characteristic != null }.keys.toList()
    }

    private fun handleIncomingData(deviceAddress: String, value: ByteArray) {
        if (value.isEmpty()) return

        when (value[0]) {
            CHUNK_START -> {
                if (value.size < 6) {
                    logError("GATT_CLIENT", "Invalid START chunk from ${deviceAddress.take(8)} - too short (${value.size} bytes)")
                    return
                }
                val expected = ((value[1].toInt() and 0xFF) shl 24) or
                    ((value[2].toInt() and 0xFF) shl 16) or
                    ((value[3].toInt() and 0xFF) shl 8) or
                    (value[4].toInt() and 0xFF)
                val payload = value.copyOfRange(5, value.size)
                reassemblyBuffers[deviceAddress] = ReassemblyBuffer(expectedSize = expected, data = payload.toMutableList())
                logInfo("GATT_CLIENT", "Started receiving chunked data from ${deviceAddress.take(8)}, expecting $expected bytes")
            }

            CHUNK_CONTINUE, CHUNK_END -> {
                val buffer = reassemblyBuffers[deviceAddress]
                if (buffer == null) {
                    logError("GATT_CLIENT", "Received chunk without START from ${deviceAddress.take(8)} (${value.size} bytes)")
                    return
                }
                val payload = value.copyOfRange(1, value.size)
                buffer.data.addAll(payload.toList())
                if (value[0] == CHUNK_END) {
                    val completeData = buffer.data.toByteArray()
                    reassemblyBuffers.remove(deviceAddress)
                    if (completeData.size != buffer.expectedSize) {
                        logError(
                            "GATT_CLIENT",
                            "Completed chunked transfer from ${deviceAddress.take(8)} but size mismatch: expected ${buffer.expectedSize}, got ${completeData.size}"
                        )
                    } else {
                        logInfo(
                            "GATT_CLIENT",
                            "Completed chunked transfer from ${deviceAddress.take(8)}: ${completeData.size} bytes"
                        )
                    }
                    delegate?.onCharacteristicRead(deviceAddress, completeData)
                }
            }

            else -> delegate?.onCharacteristicRead(deviceAddress, value)
        }
    }
}

interface IosGattClientConnectionDelegate {
    fun onConnectionSuccess(deviceAddress: String)
    fun onConnectionFailure(deviceAddress: String, reason: String)
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData {
    return usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    return ByteArray(length.toInt()).apply {
        usePinned { pinned ->
            platform.posix.memcpy(pinned.addressOf(0), bytes, length)
        }
    }
}
