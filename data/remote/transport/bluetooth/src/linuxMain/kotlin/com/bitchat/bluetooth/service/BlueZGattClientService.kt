package com.bitchat.bluetooth.service

import com.bitchat.bluetooth.protocol.logDebug
import com.bitchat.bluetooth.protocol.logError
import com.bitchat.bluetooth.protocol.logInfo
import gattlib.*
import kotlinx.cinterop.*
import kotlinx.coroutines.delay
import platform.posix.size_t

/**
 * BlueZ GATT Client Service using GattLib.
 *
 * Implements Central role GATT operations:
 * - Connecting to peripherals
 * - Service/characteristic discovery
 * - Writing characteristics (with chunking for large payloads)
 * - Receiving notifications
 */
@OptIn(ExperimentalForeignApi::class)
class BlueZGattClientService(
    private val manager: BlueZManager
) : GattClientService, BlueZManager.GattDelegate {

    companion object {
        private const val TAG = "BLUEZ_CLIENT"

        // Chunking protocol constants (must match iOS/Android implementations)
        private const val CHUNK_START: Byte = -4     // 0xFC
        private const val CHUNK_CONTINUE: Byte = -3  // 0xFD
        private const val CHUNK_END: Byte = -2       // 0xFE
        private const val MAX_CHUNK_SIZE = 499       // Leave room for chunk header
        private const val CHUNK_DELAY_MS = 25L       // Delay between chunks
    }

    // Active connections by device address
    private data class DeviceConnection(
        val address: String,
        val connection: CPointer<gattlib_connection_t>,
        var isReady: Boolean = false
    )

    private val connections = mutableMapOf<String, DeviceConnection>()
    private var delegate: GattClientDelegate? = null

    // Reassembly buffers for incoming chunked data
    private data class ReassemblyBuffer(
        var expectedSize: Int = 0,
        val data: MutableList<Byte> = mutableListOf()
    )
    private val reassemblyBuffers = mutableMapOf<String, ReassemblyBuffer>()

    init {
        manager.registerGattDelegate(this)
    }

    override fun setDelegate(delegate: GattClientDelegate) {
        this.delegate = delegate
    }

    /**
     * Connect to a BLE peripheral.
     *
     * @param deviceAddress MAC address of the device
     * @return true if connection initiated successfully
     */
    suspend fun connect(deviceAddress: String): Boolean {
        if (connections.containsKey(deviceAddress)) {
            logDebug(TAG, "Already connected to $deviceAddress")
            return true
        }

        logInfo(TAG, "Connecting to $deviceAddress...")

        val adapter = manager.getAdapter() ?: run {
            if (!manager.openAdapter()) {
                logError(TAG, "Failed to open adapter")
                return false
            }
            manager.getAdapter()
        }

        if (adapter == null) {
            logError(TAG, "Adapter is null")
            return false
        }

        // Store reference for callback
        val stableRef = StableRef.create(this)

        // Try connecting with random address support (Android devices use random BLE addresses)
        // GATTLIB_CONNECTION_OPTIONS_LEGACY_BDADDR_LE_RANDOM = (1 << 1) = 2
        val connectionOptions = (GATTLIB_CONNECTION_OPTIONS_LEGACY_BDADDR_LE_PUBLIC or
                GATTLIB_CONNECTION_OPTIONS_LEGACY_BDADDR_LE_RANDOM).toULong()

        logDebug(TAG, "Initiating connection with options: $connectionOptions")

        val result = gattlib_connect(
            adapter,
            deviceAddress,
            connectionOptions,
            staticCFunction(::connectionCallback),
            stableRef.asCPointer()
        )

        if (result != GATTLIB_SUCCESS) {
            val errorHex = result.toUInt().toString(16)
            logError(TAG, "Failed to initiate connection to $deviceAddress, error: $result (0x$errorHex)")

            // Decode GattLib error
            val errorModule = (result.toUInt() and 0xF0000000u).toInt()
            val errorCode = (result.toUInt() and 0x0FFFFFFFu).toInt()
            when (errorModule) {
                0x10000000 -> logError(TAG, "  D-Bus error code: $errorCode")
                0x20000000 -> logError(TAG, "  BlueZ error code: $errorCode")
                0x30000000 -> logError(TAG, "  Unix error code: $errorCode")
                else -> logError(TAG, "  Error module: ${errorModule.toUInt().toString(16)}, code: $errorCode")
            }

            stableRef.dispose()
            return false
        }

        logDebug(TAG, "Connection initiated to $deviceAddress")
        return true
    }

    override suspend fun writeCharacteristic(deviceAddress: String, data: ByteArray): Boolean {
        logDebug(TAG, "Write ${data.size}B to ${deviceAddress.take(8)}")

        val connection = connections[deviceAddress]
        if (connection == null || !connection.isReady) {
            logError(TAG, "No ready connection for $deviceAddress")
            return false
        }

        // Use chunking for large payloads
        return if (data.size > MAX_CHUNK_SIZE) {
            writeChunked(connection, deviceAddress, data)
        } else {
            writeSingle(connection, data)
        }
    }

    private fun writeSingle(connection: DeviceConnection, data: ByteArray): Boolean = memScoped {
        val uuid = alloc<uuid_t>()
        gattlib_string_to_uuid(
            BlueZManager.CHARACTERISTIC_UUID,
            BlueZManager.CHARACTERISTIC_UUID.length.toULong(),
            uuid.ptr
        )

        val buffer = data.toCValues()
        val result = gattlib_write_without_response_char_by_uuid(
            connection.connection,
            uuid.ptr,
            buffer,
            data.size.toULong()
        )

        if (result == GATTLIB_SUCCESS) {
            delegate?.onWriteSuccess(connection.address)
            true
        } else {
            logError(TAG, "Write failed: $result")
            delegate?.onWriteFailure(connection.address, "Write error: $result")
            false
        }
    }

    private suspend fun writeChunked(
        connection: DeviceConnection,
        deviceAddress: String,
        data: ByteArray
    ): Boolean {
        val totalSize = data.size
        var offset = 0
        var chunkNumber = 0
        val totalChunks = (totalSize + MAX_CHUNK_SIZE - 1) / MAX_CHUNK_SIZE

        logInfo(TAG, "Chunking $totalSize bytes into $totalChunks chunks for ${deviceAddress.take(8)}")

        while (offset < totalSize) {
            val remaining = totalSize - offset
            val payloadSize = minOf(remaining, MAX_CHUNK_SIZE)
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
            if (!writeSingle(connection, chunk)) {
                logError(TAG, "Failed to write chunk ${chunkNumber + 1}/$totalChunks")
                return false
            }

            chunkNumber++
            logDebug(TAG, "Wrote chunk $chunkNumber/$totalChunks (${chunk.size} bytes)")

            offset += payloadSize

            // Delay between chunks to prevent overwhelming the BLE stack
            if (!isLast) {
                delay(CHUNK_DELAY_MS)
            }
        }

        logInfo(TAG, "Successfully wrote all $totalChunks chunks ($totalSize bytes) to ${deviceAddress.take(8)}")
        delegate?.onWriteSuccess(deviceAddress)
        return true
    }

    override suspend fun disconnect(deviceAddress: String) {
        connections[deviceAddress]?.let { connection ->
            logInfo(TAG, "Disconnecting from $deviceAddress")
            gattlib_disconnect(connection.connection, true)
            connections.remove(deviceAddress)
            reassemblyBuffers.remove(deviceAddress)
        }
    }

    override suspend fun disconnectAll() {
        logInfo(TAG, "Disconnecting all (${connections.size} connections)")
        connections.values.forEach { connection ->
            gattlib_disconnect(connection.connection, false)
        }
        connections.clear()
        reassemblyBuffers.clear()
    }

    /**
     * Get list of connected device addresses that are ready for communication.
     */
    fun getReadyDeviceAddresses(): List<String> {
        return connections.filterValues { it.isReady }.keys.toList()
    }

    // BlueZManager.GattDelegate implementation

    override fun onConnected(address: String, connection: CPointer<gattlib_connection_t>) {
        logInfo(TAG, "Connected to $address")

        // Store connection
        connections[address] = DeviceConnection(address, connection, isReady = false)

        // Discover services and characteristics
        discoverServices(address, connection)
    }

    override fun onDisconnected(address: String, error: String?) {
        logInfo(TAG, "Disconnected from $address${error?.let { ": $it" } ?: ""}")
        connections.remove(address)
        reassemblyBuffers.remove(address)
    }

    override fun onNotification(address: String, uuid: CValue<uuid_t>, data: ByteArray) {
        handleIncomingData(address, data)
    }

    private fun discoverServices(address: String, connection: CPointer<gattlib_connection_t>) {
        logDebug(TAG, "Discovering services for $address")

        memScoped {
            val servicesPtr = alloc<CPointerVar<gattlib_primary_service_t>>()
            val servicesCount = alloc<IntVar>()

            val result = gattlib_discover_primary(connection, servicesPtr.ptr, servicesCount.ptr)

            if (result != GATTLIB_SUCCESS) {
                logError(TAG, "Service discovery failed for $address: $result")
                return
            }

            val count = servicesCount.value
            logDebug(TAG, "Found $count services")

            // Look for our service
            val services = servicesPtr.value
            if (services != null) {
                for (i in 0 until count) {
                    val service = services[i]
                    val uuidString = manager.uuidToString(service.uuid)
                    logDebug(TAG, "Service: $uuidString")

                    if (uuidString.equals(BlueZManager.SERVICE_UUID, ignoreCase = true)) {
                        discoverCharacteristics(address, connection, service)
                        break
                    }
                }
            }
        }
    }

    private fun discoverCharacteristics(
        address: String,
        connection: CPointer<gattlib_connection_t>,
        service: gattlib_primary_service_t
    ) {
        logDebug(TAG, "Discovering characteristics for service")

        memScoped {
            val charsPtr = alloc<CPointerVar<gattlib_characteristic_t>>()
            val charsCount = alloc<IntVar>()

            val result = gattlib_discover_char_range(
                connection,
                service.attr_handle_start,
                service.attr_handle_end,
                charsPtr.ptr,
                charsCount.ptr
            )

            if (result != GATTLIB_SUCCESS) {
                logError(TAG, "Characteristic discovery failed: $result")
                return
            }

            val count = charsCount.value
            logDebug(TAG, "Found $count characteristics")

            val chars = charsPtr.value
            if (chars != null) {
                for (i in 0 until count) {
                    val char = chars[i]
                    val uuidString = manager.uuidToString(char.uuid)
                    logDebug(TAG, "Characteristic: $uuidString")

                    if (uuidString.equals(BlueZManager.CHARACTERISTIC_UUID, ignoreCase = true)) {
                        // Enable notifications
                        enableNotifications(address, connection, char.uuid)

                        // Mark connection as ready
                        connections[address]?.isReady = true
                        logInfo(TAG, "Connection ready for $address")
                        break
                    }
                }
            }
        }
    }

    private fun enableNotifications(
        address: String,
        connection: CPointer<gattlib_connection_t>,
        uuid: uuid_t
    ) {
        logDebug(TAG, "Enabling notifications for $address")

        memScoped {
            // Register notification handler
            val stableRef = StableRef.create(NotificationContext(this@BlueZGattClientService, address))

            gattlib_register_notification(
                connection,
                staticCFunction(::notificationCallback),
                stableRef.asCPointer()
            )

            // Start notifications
            val result = gattlib_notification_start(connection, uuid.ptr)
            if (result != GATTLIB_SUCCESS) {
                logError(TAG, "Failed to start notifications: $result")
            } else {
                logDebug(TAG, "Notifications enabled")
            }
        }
    }

    private fun handleIncomingData(deviceAddress: String, value: ByteArray) {
        if (value.isEmpty()) return

        when (value[0]) {
            CHUNK_START -> {
                if (value.size < 6) {
                    logError(TAG, "Invalid START chunk from ${deviceAddress.take(8)} - too short (${value.size} bytes)")
                    return
                }
                val expected = ((value[1].toInt() and 0xFF) shl 24) or
                        ((value[2].toInt() and 0xFF) shl 16) or
                        ((value[3].toInt() and 0xFF) shl 8) or
                        (value[4].toInt() and 0xFF)
                val payload = value.copyOfRange(5, value.size)
                reassemblyBuffers[deviceAddress] = ReassemblyBuffer(
                    expectedSize = expected,
                    data = payload.toMutableList()
                )
                logInfo(TAG, "Started receiving chunked data from ${deviceAddress.take(8)}, expecting $expected bytes")
            }

            CHUNK_CONTINUE, CHUNK_END -> {
                val buffer = reassemblyBuffers[deviceAddress]
                if (buffer == null) {
                    logError(TAG, "Received chunk without START from ${deviceAddress.take(8)}")
                    return
                }
                val payload = value.copyOfRange(1, value.size)
                buffer.data.addAll(payload.toList())

                if (value[0] == CHUNK_END) {
                    val completeData = buffer.data.toByteArray()
                    reassemblyBuffers.remove(deviceAddress)
                    if (completeData.size != buffer.expectedSize) {
                        logError(TAG, "Chunked transfer size mismatch: expected ${buffer.expectedSize}, got ${completeData.size}")
                    } else {
                        logInfo(TAG, "Completed chunked transfer from ${deviceAddress.take(8)}: ${completeData.size} bytes")
                    }
                    delegate?.onCharacteristicRead(deviceAddress, completeData)
                }
            }

            else -> delegate?.onCharacteristicRead(deviceAddress, value)
        }
    }
}

/**
 * Context for notification callbacks.
 */
private data class NotificationContext(
    val service: BlueZGattClientService,
    val address: String
)

/**
 * Static callback for GattLib connection events.
 */
@OptIn(ExperimentalForeignApi::class)
private fun connectionCallback(
    adapter: CPointer<gattlib_adapter_t>?,
    dst: CPointer<ByteVar>?,
    connection: CPointer<gattlib_connection_t>?,
    error: Int,
    userData: COpaquePointer?
) {
    if (userData == null) return

    val service = userData.asStableRef<BlueZGattClientService>().get()
    val address = dst?.toKString() ?: return

    if (error != GATTLIB_SUCCESS || connection == null) {
        service.onDisconnected(address, "Connection error: $error")
    } else {
        service.onConnected(address, connection)
    }
}

/**
 * Static callback for GattLib notifications.
 */
@OptIn(ExperimentalForeignApi::class)
private fun notificationCallback(
    uuid: CPointer<uuid_t>?,
    data: CPointer<UByteVar>?,
    dataLength: size_t,
    userData: COpaquePointer?
) {
    if (userData == null || data == null || uuid == null) return

    val context = userData.asStableRef<NotificationContext>().get()

    // Convert data to ByteArray
    val bytes = ByteArray(dataLength.toInt()) { i ->
        data[i].toByte()
    }

    // Notify through the service's internal handler
    context.service.onNotificationReceived(context.address, bytes)
}

/**
 * Extension method to allow static callback to invoke internal notification handling.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun BlueZGattClientService.onNotificationReceived(address: String, data: ByteArray) {
    // This triggers handleIncomingData through the GattDelegate interface
    // by having the manager call back to us
    val uuid = memScoped {
        val u = alloc<uuid_t>()
        gattlib.gattlib_string_to_uuid(
            BlueZManager.CHARACTERISTIC_UUID,
            BlueZManager.CHARACTERISTIC_UUID.length.toULong(),
            u.ptr
        )
        u.readValue()
    }
    onNotification(address, uuid, data)
}
