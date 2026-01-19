package com.bitchat.bluetooth.service

import com.bitchat.bluetooth.protocol.logError
import com.bitchat.bluetooth.protocol.logInfo
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreBluetooth.CBAttributePermissionsReadable
import platform.CoreBluetooth.CBAttributePermissionsWriteable
import platform.CoreBluetooth.CBCentral
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicPropertyNotify
import platform.CoreBluetooth.CBCharacteristicPropertyRead
import platform.CoreBluetooth.CBCharacteristicPropertyWrite
import platform.CoreBluetooth.CBCharacteristicPropertyWriteWithoutResponse
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBMutableCharacteristic
import platform.CoreBluetooth.CBMutableService
import platform.CoreBluetooth.CBPeripheralManager
import platform.CoreBluetooth.CBPeripheralManagerDelegateProtocol
import platform.CoreBluetooth.CBPeripheralManagerOptionRestoreIdentifierKey
import platform.CoreBluetooth.CBPeripheralManagerRestoredStateServicesKey
import platform.CoreBluetooth.CBService
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSData
import platform.Foundation.create
import platform.darwin.NSObject
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_queue_t

/**
 * iOS stub for GATT Server Service
 */
class IosGattServerService : GattServerService {
    private val queue: dispatch_queue_t = dispatch_queue_create("com.bitchat.bluetooth.peripheral", null)
    private val delegateProxy = PeripheralDelegate()
    private val peripheralManager = CBPeripheralManager(
        delegate = delegateProxy,
        queue = queue,
        options = mapOf(CBPeripheralManagerOptionRestoreIdentifierKey to PERIPHERAL_RESTORE_ID)
    )

    private var characteristic: CBMutableCharacteristic? = null
    private var service: CBMutableService? = null
    private var isActive = false
    private var shouldResumeAdvertising = false
    private val subscribedCentrals = mutableSetOf<CBCentral>()
    private data class ReassemblyBuffer(var expectedSize: Int = 0, val data: MutableList<Byte> = mutableListOf())
    private val reassemblyBuffers = mutableMapOf<String, ReassemblyBuffer>()

    private val readyToUpdateChannel = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    private var delegate: GattServerDelegate? = null

    override suspend fun startAdvertising() {
        if (isActive) {
            return
        }

        shouldResumeAdvertising = true

        if (peripheralManager.state != CBManagerStatePoweredOn) {
            return
        }

        setupServiceIfNeeded()
        startBleAdvertising()
        isActive = true
    }

    override suspend fun stopAdvertising() {
        shouldResumeAdvertising = false
        if (!isActive) {
            peripheralManager.stopAdvertising()
            peripheralManager.removeAllServices()
            return
        }

        isActive = false
        subscribedCentrals.clear()
        peripheralManager.stopAdvertising()
        peripheralManager.removeAllServices()
        characteristic = null
        service = null
    }

    override suspend fun onCharacteristicWriteRequest(data: ByteArray, deviceAddress: String) {
        handleIncomingData(data, deviceAddress)
    }

    override suspend fun notifyCharacteristic(deviceAddress: String, data: ByteArray): Boolean {
        val targetCentral = subscribedCentrals.firstOrNull {
            it.identifier.UUIDString == deviceAddress
        }
        if (targetCentral == null || characteristic == null) {
            return false
        }

        // Use chunking for large payloads (BLE MTU is typically ~512 bytes)
        val maxChunkSize = 499 // Leave room for chunk header
        if (data.size > maxChunkSize) {
            return notifyChunked(targetCentral, deviceAddress, data, maxChunkSize)
        }

        val payload = data.toNSData()
        return peripheralManager.updateValue(
            payload,
            characteristic!!,
            listOf(targetCentral)
        )
    }

    private suspend fun notifyChunked(
        central: CBCentral,
        deviceAddress: String,
        data: ByteArray,
        maxPayloadSize: Int
    ): Boolean {
        val char = characteristic ?: return false
        val totalSize = data.size
        var offset = 0
        var chunkNumber = 0
        val totalChunks = (totalSize + maxPayloadSize - 1) / maxPayloadSize

        logInfo("IOS_GATT_SERVER", "Chunking $totalSize bytes into $totalChunks chunks for ${deviceAddress.take(8)}")

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

            // Notify chunk with proper queue handling
            val payload = chunk.toNSData()
            var success = peripheralManager.updateValue(payload, char, listOf(central))

            // If queue is full, wait for ready callback and retry
            var retryCount = 0
            val maxRetries = 10
            while (!success && retryCount < maxRetries) {
                retryCount++
                logInfo("IOS_GATT_SERVER", "Queue full for chunk $chunkNumber/$totalChunks, waiting for ready (attempt $retryCount)")

                // Wait for the ready callback with timeout
                val readyResult = kotlinx.coroutines.withTimeoutOrNull(500) {
                    readyToUpdateChannel.receive()
                }

                if (readyResult == null) {
                    // Timeout - try anyway with a small delay
                    kotlinx.coroutines.delay(50)
                }

                success = peripheralManager.updateValue(payload, char, listOf(central))
            }

            if (!success) {
                logError("IOS_GATT_SERVER", "Failed to send chunk $chunkNumber/$totalChunks after $maxRetries retries")
                return false
            }

            chunkNumber++
            offset += payloadSize

            // Small delay between chunks to prevent overwhelming the BLE stack
            if (!isLast) {
                kotlinx.coroutines.delay(25)
            }
        }

        logInfo("IOS_GATT_SERVER", "Successfully notified all $totalChunks chunks ($totalSize bytes) to ${deviceAddress.take(8)}")
        return true
    }

    override fun setDelegate(delegate: GattServerDelegate) {
        this.delegate = delegate
    }

    private fun handleIncomingData(value: ByteArray, deviceAddress: String) {
        if (value.isEmpty()) {
            return
        }

        when (value[0]) {
            CHUNK_START -> {
                if (value.size < 6) {
                    logError("IOS_GATT_SERVER", "Invalid START chunk from $deviceAddress - too short (${value.size} bytes)")
                    return
                }
                val expected = ((value[1].toInt() and 0xFF) shl 24) or
                    ((value[2].toInt() and 0xFF) shl 16) or
                    ((value[3].toInt() and 0xFF) shl 8) or
                    (value[4].toInt() and 0xFF)
                val payload = value.copyOfRange(5, value.size)
                val buffer = ReassemblyBuffer(expectedSize = expected, data = payload.toMutableList())
                reassemblyBuffers[deviceAddress] = buffer
                logInfo("IOS_GATT_SERVER", "Started receiving chunked data from $deviceAddress, expecting $expected bytes")
            }

            CHUNK_CONTINUE, CHUNK_END -> {
                val buffer = reassemblyBuffers[deviceAddress]
                if (buffer == null) {
                    logError("IOS_GATT_SERVER", "Received chunk without START from $deviceAddress (${value.size} bytes)")
                    return
                }
                val payload = value.copyOfRange(1, value.size)
                buffer.data.addAll(payload.toList())
                if (value[0] == CHUNK_END) {
                    val completeData = buffer.data.toByteArray()
                    reassemblyBuffers.remove(deviceAddress)
                    if (completeData.size != buffer.expectedSize) {
                        logError(
                            "IOS_GATT_SERVER",
                            "Completed chunked transfer from $deviceAddress but size mismatch: expected ${buffer.expectedSize}, got ${completeData.size}"
                        )
                    } else {
                        logInfo(
                            "IOS_GATT_SERVER",
                            "Completed chunked transfer from $deviceAddress: ${completeData.size} bytes"
                        )
                    }
                    delegate?.onDataReceived(completeData, deviceAddress)
                }
            }

            else -> {
                delegate?.onDataReceived(value, deviceAddress)
            }
        }
    }

    private fun setupServiceIfNeeded() {
        if (service != null && characteristic != null) {
            return
        }

        val serviceUuid = CBUUID.UUIDWithString(SERVICE_UUID)
        val characteristicUuid = CBUUID.UUIDWithString(CHARACTERISTIC_UUID)

        val properties = CBCharacteristicPropertyRead or
                CBCharacteristicPropertyWrite or
                CBCharacteristicPropertyWriteWithoutResponse or
                CBCharacteristicPropertyNotify
        val permissions = CBAttributePermissionsReadable or CBAttributePermissionsWriteable

        val mutableCharacteristic = CBMutableCharacteristic(
            type = characteristicUuid,
            properties = properties,
            value = null,
            permissions = permissions
        )

        val mutableService = CBMutableService(type = serviceUuid, primary = true).apply {
            setCharacteristics(listOf(mutableCharacteristic))
        }

        characteristic = mutableCharacteristic
        service = mutableService
        peripheralManager.removeAllServices()
        peripheralManager.addService(mutableService)
    }

    private fun startBleAdvertising() {
        val serviceUuid = CBUUID.UUIDWithString(SERVICE_UUID)
        val advertisement: Map<Any?, Any?> = mapOf(
            platform.CoreBluetooth.CBAdvertisementDataServiceUUIDsKey to listOf(serviceUuid)
        )
        peripheralManager.startAdvertising(advertisement)
    }

    private inner class PeripheralDelegate : NSObject(), CBPeripheralManagerDelegateProtocol {
        override fun peripheralManagerDidUpdateState(peripheral: CBPeripheralManager) {
            if (peripheral.state != CBManagerStatePoweredOn) {
                return
            }

            if (shouldResumeAdvertising) {
                setupServiceIfNeeded()
                startBleAdvertising()
                isActive = true
            }
        }

        override fun peripheralManager(
            peripheral: CBPeripheralManager,
            didReceiveWriteRequests: List<*>
        ) {
            didReceiveWriteRequests.forEach { request ->
                val writeRequest = request as? platform.CoreBluetooth.CBATTRequest ?: return@forEach
                val requestCharacteristic = writeRequest.characteristic
                val value = writeRequest.value?.toByteArray()
                if (requestCharacteristic.UUID == characteristic?.UUID && value != null) {
                    val central = writeRequest.central
                    val deviceAddress = central.identifier.UUIDString

                    // Ensure the write-sender is tracked so we can notify them back
                    // This handles cases where didSubscribeToCharacteristic wasn't called
                    val alreadySubscribed = subscribedCentrals.any { it.identifier.UUIDString == deviceAddress }
                    if (!alreadySubscribed) {
                        logInfo("IOS_GATT_SERVER", "Adding write-sender to subscribers: ${deviceAddress.take(8)}")
                        subscribedCentrals.add(central)
                        delegate?.onClientConnected(deviceAddress)
                    }

                    handleIncomingData(value, deviceAddress)
                }
                peripheral.respondToRequest(
                    request = writeRequest,
                    withResult = platform.CoreBluetooth.CBATTErrorSuccess,
                )
            }
        }

        @kotlinx.cinterop.ObjCSignatureOverride
        override fun peripheralManager(
            peripheral: CBPeripheralManager,
            central: CBCentral,
            didSubscribeToCharacteristic: CBCharacteristic
        ) {
            subscribedCentrals.add(central)
            delegate?.onClientConnected(central.identifier.UUIDString)
        }

        @kotlinx.cinterop.ObjCSignatureOverride
        override fun peripheralManager(
            peripheral: CBPeripheralManager,
            central: CBCentral,
            didUnsubscribeFromCharacteristic: CBCharacteristic
        ) {
            subscribedCentrals.remove(central)
            delegate?.onClientDisconnected(central.identifier.UUIDString)
        }

        override fun peripheralManagerIsReadyToUpdateSubscribers(peripheral: CBPeripheralManager) {
            // Signal that the queue is ready for more data
            logInfo("IOS_GATT_SERVER", "Peripheral manager ready to update subscribers")
            readyToUpdateChannel.trySend(Unit)
        }

        override fun peripheralManager(
            peripheral: CBPeripheralManager,
            willRestoreState: Map<Any?, *>
        ) {
            val services = willRestoreState[CBPeripheralManagerRestoredStateServicesKey] as? List<*>
            services?.forEach { restoredService ->
                val cbService = restoredService as? CBService ?: return@forEach
                if (cbService.UUID == CBUUID.UUIDWithString(SERVICE_UUID)) {
                    service = cbService as? CBMutableService
                    val restoredCharacteristic = cbService.characteristics?.firstOrNull {
                        (it as? CBCharacteristic)?.UUID == CBUUID.UUIDWithString(CHARACTERISTIC_UUID)
                    } as? CBMutableCharacteristic
                    characteristic = restoredCharacteristic
                }
            }
        }
    }

    companion object {
        private const val PERIPHERAL_RESTORE_ID = "com.bitchat.bluetooth.peripheral"
        private const val SERVICE_UUID = "F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C"
        private const val CHARACTERISTIC_UUID = "A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5D"
        // Use high byte values to avoid conflict with packet types (0x01=ANNOUNCE, etc.)
        // 0xFC=-4, 0xFD=-3, 0xFE=-2 as signed bytes
        private const val CHUNK_START: Byte = -4     // 0xFC (252 unsigned)
        private const val CHUNK_CONTINUE: Byte = -3  // 0xFD (253 unsigned)
        private const val CHUNK_END: Byte = -2       // 0xFE (254 unsigned)
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

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData {
    return usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
    }
}
