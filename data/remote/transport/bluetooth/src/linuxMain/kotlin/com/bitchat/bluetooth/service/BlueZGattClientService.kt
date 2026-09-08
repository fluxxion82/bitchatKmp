package com.bitchat.bluetooth.service

import com.bitchat.bluetooth.protocol.logDebug
import com.bitchat.bluetooth.protocol.logError
import com.bitchat.bluetooth.protocol.logInfo
import gattlib.*
import kotlinx.cinterop.*
import kotlinx.coroutines.delay
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicReference
import platform.posix.free
import platform.posix.size_t

/**
 * BlueZ GATT Client Service using GattLib.
 *
 * Implements Central role GATT operations:
 * - Connecting to peripherals
 * - Service/characteristic discovery
 * - Writing characteristics (with chunking for large payloads)
 * - Receiving notifications
 *
 * Threading: gattlib runs the connect callback on a thread it spawns per connection
 * (`_gattlib_connected_device_thread`) and the disconnect callback synchronously on the GLib
 * main-loop thread, while application code calls in from coroutine threads. Everything shared
 * between them is therefore atomic, and every gattlib call on a connection is gated on that
 * connection still being alive - see [DeviceConnection].
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

    /**
     * A live gattlib connection.
     *
     * [isAlive] is cleared the instant BlueZ reports the peer gone, from gattlib's disconnect
     * handler, which runs *before* `gattlib_connection_free()` frees the connection's D-Bus object
     * list. gattlib leaves the freed list pointer in place and its own `gattlib_connection_is_valid()`
     * only checks adapter-list membership, so any gattlib call made after that point walks freed
     * memory. This flag is the guard that keeps us out.
     */
    private class DeviceConnection(
        val address: String,
        val connection: CPointer<gattlib_connection_t>
    ) {
        private val aliveFlag = AtomicInt(1)
        private val readyFlag = AtomicInt(0)

        val isAlive: Boolean get() = aliveFlag.value == 1
        val isReady: Boolean get() = readyFlag.value == 1

        /** Returns true for the caller that transitioned the connection from alive to dead. */
        fun markDead(): Boolean = aliveFlag.compareAndSet(1, 0)

        fun markReady() {
            readyFlag.value = 1
        }
    }

    // Active connections by device address. Copy-on-write: mutated from gattlib's threads and read
    // from the application's coroutine threads.
    private val connections = AtomicReference<Map<String, DeviceConnection>>(emptyMap())

    // Bumped every time any peer's link goes away.
    //
    // gattlib snapshots the WHOLE BlueZ object tree per connection
    // (`connection->backend.dbus_objects = g_dbus_object_manager_get_objects(...)`, gattlib.c:47)
    // and every discovery, write and notification walks that snapshot. So a peer we are not talking
    // to disappearing is just as dangerous as our own: BlueZ removes its GATT objects, gattlib
    // creates proxies for them anyway and reads properties that are no longer cached, and it does
    // not NULL-check what comes back. Any discovery that straddles such a teardown is abandoned.
    private val topologyEpoch = AtomicInt(0)

    // gattlib keeps the connect/disconnect user_data pointer for the lifetime of the connection and
    // never hands it back, so a single service-lifetime ref is used instead of one per connect.
    private val selfRef = StableRef.create(this)

    // Notification contexts are likewise retained by gattlib for the lifetime of a connection, so
    // they are created once per peer address and reused across reconnects.
    private val notificationContexts = AtomicReference<Map<String, COpaquePointer>>(emptyMap())

    private var delegate: GattClientDelegate? = null

    /** Invoked from a gattlib thread when a peer becomes usable for mesh traffic. */
    var onConnectionReady: ((String) -> Unit)? = null

    /** Invoked from a gattlib thread when a peer is dropped, for any reason. */
    var onConnectionLost: ((String) -> Unit)? = null

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

    /** What gattlib made of a connection request. */
    enum class ConnectOutcome {
        /** gattlib accepted the request; a callback will follow, or the reaper's deadline will. */
        STARTED,

        /**
         * gattlib still owns an earlier attempt to this address and will not start another.
         *
         * Nothing here can hurry that along: `gattlib_disconnect` needs a connection pointer only a
         * successful connect produces, and it refuses a device still in `CONNECTING`. The address
         * belongs to gattlib until gattlib lets go.
         */
        BUSY,

        /** gattlib refused outright. No callback is coming. */
        REFUSED
    }

    /**
     * Connect to a BLE peripheral.
     *
     * @param deviceAddress MAC address of the device
     * @return what gattlib made of the request
     */
    suspend fun connect(deviceAddress: String): ConnectOutcome {
        if (connections.value.containsKey(deviceAddress)) {
            logDebug(TAG, "Already connected to $deviceAddress")
            return ConnectOutcome.STARTED
        }

        logInfo(TAG, "Connecting to $deviceAddress...")

        val adapter = manager.getAdapter() ?: run {
            if (!manager.openAdapter()) {
                logError(TAG, "Failed to open adapter")
                return ConnectOutcome.REFUSED
            }
            manager.getAdapter()
        }

        if (adapter == null) {
            logError(TAG, "Adapter is null")
            return ConnectOutcome.REFUSED
        }

        // Try connecting with random address support (Android devices use random BLE addresses)
        // GATTLIB_CONNECTION_OPTIONS_LEGACY_BDADDR_LE_RANDOM = (1 << 1) = 2
        val connectionOptions = (GATTLIB_CONNECTION_OPTIONS_LEGACY_BDADDR_LE_PUBLIC or
                GATTLIB_CONNECTION_OPTIONS_LEGACY_BDADDR_LE_RANDOM).toULong()

        val result = gattlib_connect(
            adapter,
            deviceAddress,
            connectionOptions,
            staticCFunction(::connectionCallback),
            selfRef.asCPointer()
        )

        if (result == GATTLIB_BUSY) {
            logDebug(TAG, "gattlib still owns an attempt to $deviceAddress")
            return ConnectOutcome.BUSY
        }

        if (result != GATTLIB_SUCCESS) {
            logError(TAG, "Failed to initiate connection to $deviceAddress: ${describeError(result)}")
            return ConnectOutcome.REFUSED
        }

        logDebug(TAG, "Connection initiated to $deviceAddress")
        return ConnectOutcome.STARTED
    }

    override suspend fun writeCharacteristic(deviceAddress: String, data: ByteArray): Boolean {
        val connection = connections.value[deviceAddress]
        if (connection == null || !connection.isReady || !connection.isAlive) {
            logDebug(TAG, "No ready connection for ${deviceAddress.take(8)}")
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
        if (!connection.isAlive) {
            // The peer went away between the registry lookup and here; the gattlib connection is
            // already torn down, so writing to it would walk freed memory.
            delegate?.onWriteFailure(connection.address, "Peer disconnected")
            return@memScoped false
        }

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
            logDebug(TAG, "Write to ${connection.address.take(8)} failed: $result")
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

        logDebug(TAG, "Chunking $totalSize bytes into $totalChunks chunks for ${deviceAddress.take(8)}")

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
                logError(TAG, "Failed to write chunk ${chunkNumber + 1}/$totalChunks to ${deviceAddress.take(8)}")
                return false
            }

            chunkNumber++

            offset += payloadSize

            // Delay between chunks to prevent overwhelming the BLE stack
            if (!isLast) {
                delay(CHUNK_DELAY_MS)
            }
        }

        logInfo(TAG, "Wrote $totalChunks chunks ($totalSize bytes) to ${deviceAddress.take(8)}")
        delegate?.onWriteSuccess(deviceAddress)
        return true
    }

    override suspend fun disconnect(deviceAddress: String) {
        val entry = removeConnection(deviceAddress) ?: return
        logInfo(TAG, "Disconnecting from $deviceAddress")
        reassemblyBuffers.remove(deviceAddress)
        if (entry.markDead()) {
            gattlib_disconnect(entry.connection, true)
        }
    }

    override suspend fun disconnectAll() {
        val entries = takeAllConnections()
        if (entries.isEmpty()) return

        logInfo(TAG, "Disconnecting all (${entries.size} connections)")
        entries.forEach { entry ->
            if (entry.markDead()) {
                gattlib_disconnect(entry.connection, false)
            }
        }
        reassemblyBuffers.clear()
    }

    /**
     * Get list of connected device addresses that are ready for communication.
     */
    fun getReadyDeviceAddresses(): List<String> =
        connections.value.values.filter { it.isReady && it.isAlive }.map { it.address }

    // BlueZManager.GattDelegate implementation

    override fun onConnected(address: String, connection: CPointer<gattlib_connection_t>) {
        logInfo(TAG, "Connected to $address")

        val entry = DeviceConnection(address, connection)
        putConnection(entry)?.markDead()

        // Register the disconnect handler BEFORE discovery. gattlib offers no other way to learn
        // that a peer went away, and without it nothing ever leaves `connections`: the entry stays
        // ready forever and every later broadcast calls
        // gattlib_write_without_response_char_by_uuid() -> get_characteristic_from_uuid()
        // (gattlib_char.c:139), which walks connection->backend.dbus_objects - a GList that
        // gattlib_connection_free() (gattlib.c:338) freed without clearing the pointer.
        val registered = gattlib_register_on_disconnect(
            connection,
            staticCFunction(::disconnectionCallback),
            selfRef.asCPointer()
        )
        if (registered != GATTLIB_SUCCESS) {
            abandon(entry, "cannot watch for disconnect (error $registered)")
            return
        }

        discoverServices(entry)
    }

    /**
     * Render a gattlib status as something a journal reader can act on.
     *
     * gattlib packs the failing subsystem into the top nibble and, for D-Bus, the GLib error domain
     * and code into the low bits (`GATTLIB_ERROR_DBUS_WITH_ERROR`, `include/gattlib.h`). BlueZ
     * exposes no disconnect reason on D-Bus at all, so this is the only reason code available to
     * us; the matching text is in gattlib's own log line, which for every failed connect on this
     * device reads `org.bluez.Error.Failed: le-connection-abort-by-local` -- the host cancelling
     * its own connection attempt because the radio was also scanning or already initiating.
     */
    private fun describeError(status: Int): String {
        val raw = status.toUInt()
        val module = when ((raw and 0xF0000000u).toLong()) {
            0x10000000L -> "D-Bus"
            0x20000000L -> "BlueZ"
            0x30000000L -> "Unix"
            0x80000000L -> "gattlib"
            else -> "module ${(raw shr 28).toString(16)}"
        }
        val domain = ((raw shr 8) and 0xFFFFFu).toInt()
        val code = (raw and 0xFFu).toInt()
        return "$status (0x${raw.toString(16)}, $module domain $domain code $code)"
    }

    override fun onDisconnected(address: String, error: String?) {
        // Bumped first: a discovery on another thread should see the topology change as early as
        // possible. Only real link losses bump it - abandon() does not, so one peer being dropped
        // never cascades into dropping the peers that are still fine.
        topologyEpoch.incrementAndGet()
        val entry = removeConnection(address)
        entry?.markDead()
        reassemblyBuffers.remove(address)
        logInfo(TAG, "Disconnected from $address${error?.let { ": $it" } ?: ""}")
        onConnectionLost?.invoke(address)
    }

    override fun onNotification(address: String, uuid: CValue<uuid_t>, data: ByteArray) {
        handleIncomingData(address, data)
    }

    /**
     * gattlib's disconnect handler. Called synchronously on the GLib main-loop thread while
     * gattlib holds its global recursive mutex and immediately before it frees the connection's
     * D-Bus objects, so it must stay short and must not call back into gattlib.
     */
    internal fun onGattlibDisconnected(connection: CPointer<gattlib_connection_t>?) {
        val raw = connection?.rawValue ?: return
        val entry = connections.value.values.firstOrNull { it.connection.rawValue == raw } ?: return
        onDisconnected(entry.address, null)
    }

    /**
     * Drop a peer we cannot use. Takes it out of the registry so no further gattlib call is made on
     * its connection, releases the BLE link if it is still up, and leaves the mesh service running.
     * The scanner will offer the peer again under BlueZConnectionService's existing backoff, so
     * this never turns into a retry storm.
     */
    private fun abandon(entry: DeviceConnection, reason: String) {
        removeConnection(entry.address)
        reassemblyBuffers.remove(entry.address)
        val wasAlive = entry.markDead()
        logInfo(TAG, "Abandoning ${entry.address}: $reason")
        if (wasAlive) {
            gattlib_disconnect(entry.connection, false)
        }
        onConnectionLost?.invoke(entry.address)
    }

    private fun discoverServices(entry: DeviceConnection) {
        if (!entry.isAlive) {
            abandon(entry, "peer gone before service discovery")
            return
        }
        val epoch = topologyEpoch.value

        memScoped {
            val servicesPtr = alloc<CPointerVar<gattlib_primary_service_t>>()
            val servicesCount = alloc<IntVar>()
            // memScoped allocations are malloc'd, not zeroed: make the out-params well defined even
            // if gattlib returns without writing them.
            servicesPtr.value = null
            servicesCount.value = 0

            // gattlib_discover_primary() calloc's the array and hands us ownership. Nothing freed
            // it. The try starts here, after the out-params are known-null, so the finally is a
            // no-op when gattlib returns without writing them. bitchatService points *into* this
            // array and discoverCharacteristics() reads its handle range, so the free has to stay
            // out here where that nested call has already returned.
            try {
                val result = gattlib_discover_primary(entry.connection, servicesPtr.ptr, servicesCount.ptr)

                if (result != GATTLIB_SUCCESS) {
                    abandon(entry, "service discovery failed ($result)")
                    return
                }

                val services = servicesPtr.value
                val count = servicesCount.value
                if (services == null || count <= 0) {
                    abandon(entry, "no GATT services reported")
                    return
                }

                var bitchatService: gattlib_primary_service_t? = null
                for (i in 0 until count) {
                    val service = services[i]
                    if (manager.uuidToString(service.uuid).equals(BlueZManager.SERVICE_UUID, ignoreCase = true)) {
                        bitchatService = service
                        break
                    }
                }

                if (bitchatService == null) {
                    abandon(entry, "no bitchat service among $count services")
                    return
                }

                // gattlib_discover_primary() is a long run of synchronous D-Bus round trips. If the peer
                // vanished during it, its D-Bus object list has already been freed, so re-entering
                // gattlib would walk freed memory.
                if (!entry.isAlive) {
                    abandon(entry, "peer disconnected during service discovery")
                    return
                }
                if (topologyEpoch.value != epoch) {
                    abandon(entry, "BLE topology changed during service discovery")
                    return
                }

                discoverCharacteristics(entry, bitchatService, count, epoch)
            } finally {
                free(servicesPtr.value)
            }
        }
    }

    private fun discoverCharacteristics(
        entry: DeviceConnection,
        service: gattlib_primary_service_t,
        serviceCount: Int,
        epoch: Int
    ) {
        val address = entry.address

        memScoped {
            val charsPtr = alloc<CPointerVar<gattlib_characteristic_t>>()
            val charsCount = alloc<IntVar>()
            charsPtr.value = null
            charsCount.value = 0

            // Same ownership as the service array: gattlib calloc's it, we free it. bitchatCharacteristic
            // is a uuid_t inside this array, and gattlib_notification_start() reads it through before
            // returning, so freeing here rather than eagerly after the scan keeps that read valid.
            try {
                val result = gattlib_discover_char_range(
                    entry.connection,
                    service.attr_handle_start,
                    service.attr_handle_end,
                    charsPtr.ptr,
                    charsCount.ptr
                )

                if (result != GATTLIB_SUCCESS) {
                    abandon(entry, "characteristic discovery failed ($result)")
                    return
                }

                if (!entry.isAlive) {
                    abandon(entry, "peer disconnected during characteristic discovery")
                    return
                }
                if (topologyEpoch.value != epoch) {
                    abandon(entry, "BLE topology changed during characteristic discovery")
                    return
                }

                val chars = charsPtr.value
                val count = charsCount.value

                var bitchatCharacteristic: uuid_t? = null
                if (chars != null) {
                    for (i in 0 until count) {
                        val characteristic = chars[i]
                        if (manager.uuidToString(characteristic.uuid)
                                .equals(BlueZManager.CHARACTERISTIC_UUID, ignoreCase = true)
                        ) {
                            bitchatCharacteristic = characteristic.uuid
                            break
                        }
                    }
                }

                if (bitchatCharacteristic == null) {
                    abandon(entry, "bitchat characteristic missing from $count characteristics")
                    return
                }

                if (!enableNotifications(entry, bitchatCharacteristic)) {
                    abandon(entry, "could not subscribe to notifications")
                    return
                }

                if (!entry.isAlive) {
                    abandon(entry, "peer disconnected during subscription")
                    return
                }

                entry.markReady()
                logInfo(TAG, "Discovery complete for $address: $serviceCount services, $count characteristics, ready")
                onConnectionReady?.invoke(address)
            } finally {
                free(charsPtr.value)
            }
        }
    }

    private fun enableNotifications(entry: DeviceConnection, uuid: uuid_t): Boolean {
        gattlib_register_notification(
            entry.connection,
            staticCFunction(::notificationCallback),
            notificationContextFor(entry.address)
        )

        val result = gattlib_notification_start(entry.connection, uuid.ptr)
        if (result != GATTLIB_SUCCESS) {
            logError(TAG, "Failed to start notifications for ${entry.address}: $result")
            return false
        }
        return true
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
                logDebug(TAG, "Started receiving chunked data from ${deviceAddress.take(8)}, expecting $expected bytes")
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

    // Copy-on-write registry helpers

    private fun putConnection(entry: DeviceConnection): DeviceConnection? {
        while (true) {
            val current = connections.value
            val previous = current[entry.address]
            if (connections.compareAndSet(current, current + (entry.address to entry))) {
                return previous
            }
        }
    }

    private fun removeConnection(address: String): DeviceConnection? {
        while (true) {
            val current = connections.value
            val previous = current[address] ?: return null
            if (connections.compareAndSet(current, current - address)) {
                return previous
            }
        }
    }

    private fun takeAllConnections(): Collection<DeviceConnection> {
        while (true) {
            val current = connections.value
            if (current.isEmpty()) return emptyList()
            if (connections.compareAndSet(current, emptyMap())) return current.values
        }
    }

    private fun notificationContextFor(address: String): COpaquePointer {
        while (true) {
            val current = notificationContexts.value
            current[address]?.let { return it }

            val created = StableRef.create(NotificationContext(this, address))
            val pointer = created.asCPointer()
            if (notificationContexts.compareAndSet(current, current + (address to pointer))) {
                return pointer
            }
            // Lost the race, and gattlib never saw this pointer: safe to release.
            created.dispose()
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
 * Runs on the per-connection thread gattlib spawns, not on any application thread.
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
 * Static callback for GattLib disconnection events.
 * Runs on the GLib main-loop thread while gattlib holds its global mutex, immediately before it
 * frees the connection's D-Bus objects.
 */
@OptIn(ExperimentalForeignApi::class)
private fun disconnectionCallback(
    connection: CPointer<gattlib_connection_t>?,
    userData: COpaquePointer?
) {
    if (userData == null) return
    userData.asStableRef<BlueZGattClientService>().get().onGattlibDisconnected(connection)
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
