package com.bitchat.bluetooth.service

import cnames.structs.DBusConnection
import cnames.structs.DBusMessage
import cnames.structs.DBusPendingCall
import com.bitchat.bluetooth.manager.BlueZObjectPath
import com.bitchat.bluetooth.manager.BroadcastTargets
import com.bitchat.bluetooth.manager.GattClientRegistry
import com.bitchat.bluetooth.protocol.logDebug
import com.bitchat.bluetooth.protocol.logError
import com.bitchat.bluetooth.protocol.logInfo
import dbus.*
import kotlinx.cinterop.*
import kotlinx.coroutines.delay

/**
 * BlueZ GATT Server Service using D-Bus.
 *
 * Implements Peripheral role GATT operations:
 * - Hosting a GATT service with characteristics
 * - Accepting connections from Central devices
 * - Handling write requests and sending notifications
 *
 * GattLib only supports Central role, so we use BlueZ D-Bus API directly
 * for Peripheral role operations.
 *
 * D-Bus Object Tree:
 * /org/bitchat/gatt
 *   └── service0                     (org.bluez.GattService1)
 *       └── char0                    (org.bluez.GattCharacteristic1)
 */
import kotlin.concurrent.AtomicInt
import kotlin.native.concurrent.Worker

// Global reference for static D-Bus callbacks
private var gattServerInstance: BlueZGattServerService? = null

// Global references for D-Bus dispatch loop (needed for Worker execution)
@OptIn(ExperimentalForeignApi::class)
private var dbusDispatchConnection: CPointer<DBusConnection>? = null
private var dbusDispatchRunning: AtomicInt? = null

@OptIn(ExperimentalForeignApi::class)
class BlueZGattServerService(
    private val manager: BlueZManager
) : GattServerService {

    companion object {
        private const val TAG = "BLUEZ_SERVER"

        // D-Bus paths
        private const val BLUEZ_SERVICE = "org.bluez"
        private const val GATT_MANAGER_IFACE = "org.bluez.GattManager1"
        private const val GATT_SERVICE_IFACE = "org.bluez.GattService1"
        private const val GATT_CHAR_IFACE = "org.bluez.GattCharacteristic1"
        private const val OBJECT_MANAGER_IFACE = "org.freedesktop.DBus.ObjectManager"
        private const val PROPERTIES_IFACE = "org.freedesktop.DBus.Properties"
        private const val INTROSPECTABLE_IFACE = "org.freedesktop.DBus.Introspectable"
        private const val ADAPTER_PATH = "/org/bluez/hci0"

        // BlueZ never calls us when a central goes away: the only inbound traffic on our own
        // object tree is WriteValue. These two match rules are how the peripheral role learns that
        // a link is gone -- a device turning Connected=false, and BlueZ dropping the device object
        // altogether, which is what an Android phone rotating its address looks like.
        private const val DEVICE_PROPERTIES_MATCH =
            "type='signal',sender='org.bluez',interface='org.freedesktop.DBus.Properties'," +
                "member='PropertiesChanged',arg0='org.bluez.Device1'"
        private const val INTERFACES_REMOVED_MATCH =
            "type='signal',sender='org.bluez'," +
                "interface='org.freedesktop.DBus.ObjectManager',member='InterfacesRemoved'"
        private const val APP_PATH = "/org/bitchat/gatt"
        private const val SERVICE_PATH = "/org/bitchat/gatt/service0"
        private const val CHAR_PATH = "/org/bitchat/gatt/service0/char0"

        // Chunking protocol constants
        private const val CHUNK_START: Byte = -4     // 0xFC
        private const val CHUNK_CONTINUE: Byte = -3  // 0xFD
        private const val CHUNK_END: Byte = -2       // 0xFE
        private const val MAX_CHUNK_SIZE = 499
        private const val CHUNK_DELAY_MS = 25L

        // Introspection XML for the application object
        private const val APP_INTROSPECT_XML = """<!DOCTYPE node PUBLIC "-//freedesktop//DTD D-BUS Object Introspection 1.0//EN" "http://www.freedesktop.org/standards/dbus/1.0/introspect.dtd">
<node>
  <interface name="org.freedesktop.DBus.ObjectManager">
    <method name="GetManagedObjects">
      <arg name="objects" type="a{oa{sa{sv}}}" direction="out"/>
    </method>
  </interface>
  <interface name="org.freedesktop.DBus.Introspectable">
    <method name="Introspect">
      <arg name="xml" type="s" direction="out"/>
    </method>
  </interface>
  <node name="service0"/>
</node>"""

        private const val SERVICE_INTROSPECT_XML = """<!DOCTYPE node PUBLIC "-//freedesktop//DTD D-BUS Object Introspection 1.0//EN" "http://www.freedesktop.org/standards/dbus/1.0/introspect.dtd">
<node>
  <interface name="org.bluez.GattService1">
    <property name="UUID" type="s" access="read"/>
    <property name="Primary" type="b" access="read"/>
  </interface>
  <interface name="org.freedesktop.DBus.Properties">
    <method name="Get">
      <arg name="interface" type="s" direction="in"/>
      <arg name="name" type="s" direction="in"/>
      <arg name="value" type="v" direction="out"/>
    </method>
    <method name="GetAll">
      <arg name="interface" type="s" direction="in"/>
      <arg name="properties" type="a{sv}" direction="out"/>
    </method>
  </interface>
  <interface name="org.freedesktop.DBus.Introspectable">
    <method name="Introspect">
      <arg name="xml" type="s" direction="out"/>
    </method>
  </interface>
  <node name="char0"/>
</node>"""

        private const val CHAR_INTROSPECT_XML = """<!DOCTYPE node PUBLIC "-//freedesktop//DTD D-BUS Object Introspection 1.0//EN" "http://www.freedesktop.org/standards/dbus/1.0/introspect.dtd">
<node>
  <interface name="org.bluez.GattCharacteristic1">
    <property name="UUID" type="s" access="read"/>
    <property name="Service" type="o" access="read"/>
    <property name="Flags" type="as" access="read"/>
    <property name="Value" type="ay" access="read"/>
    <method name="ReadValue">
      <arg name="options" type="a{sv}" direction="in"/>
      <arg name="value" type="ay" direction="out"/>
    </method>
    <method name="WriteValue">
      <arg name="value" type="ay" direction="in"/>
      <arg name="options" type="a{sv}" direction="in"/>
    </method>
    <method name="StartNotify"/>
    <method name="StopNotify"/>
  </interface>
  <interface name="org.freedesktop.DBus.Properties">
    <method name="Get">
      <arg name="interface" type="s" direction="in"/>
      <arg name="name" type="s" direction="in"/>
      <arg name="value" type="v" direction="out"/>
    </method>
    <method name="GetAll">
      <arg name="interface" type="s" direction="in"/>
      <arg name="properties" type="a{sv}" direction="out"/>
    </method>
  </interface>
  <interface name="org.freedesktop.DBus.Introspectable">
    <method name="Introspect">
      <arg name="xml" type="s" direction="out"/>
    </method>
  </interface>
</node>"""
    }

    private var delegate: GattServerDelegate? = null
    private var dbusConnection: CPointer<DBusConnection>? = null
    private var isRegistered = false
    private var isActive = false
    private var objectsRegistered = false

    // D-Bus dispatch worker
    private var dispatchWorker: Worker? = null
    private val dispatchRunning = AtomicInt(0)

    /**
     * Set by [BlueZConnectionService] so a BlueZ device-gone signal reaps the outbound link to the
     * same peer, not just the server-side client. gattlib's per-connection disconnect handler is
     * the only other thing that reports an outbound link dropping, and it does not always fire.
     */
    internal var onDeviceLinkGone: ((String) -> Unit)? = null

    /** Set by [BlueZConnectionService] so a peer BlueZ already holds can still be adopted. */
    internal var onDeviceConnected: ((String) -> Unit)? = null

    // Connected clients tracked by device address
    private val connectedClients = GattClientRegistry()

    // The connection the signal match rules were installed on, or null when they are not
    // installed. Removal is gated on it and it is cleared before the call, so a second teardown
    // is a no-op rather than a request the daemon has to reject.
    private var matchConnection: CPointer<DBusConnection>? = null

    // Reassembly buffers for chunked incoming data
    private data class ReassemblyBuffer(
        var expectedSize: Int = 0,
        val data: MutableList<Byte> = mutableListOf()
    )
    private val reassemblyBuffers = mutableMapOf<String, ReassemblyBuffer>()

    // Pending notifications (address -> data)
    private val pendingNotifications = mutableMapOf<String, ByteArray>()

    override fun setDelegate(delegate: GattServerDelegate) {
        this.delegate = delegate
    }

    override suspend fun startAdvertising() {
        if (isActive) {
            logDebug(TAG, "Already active")
            return
        }

        logInfo(TAG, "Starting GATT server...")

        if (!initDbusConnection()) {
            logError(TAG, "Failed to initialize D-Bus connection")
            return
        }

        if (!registerGattApplication()) {
            logError(TAG, "Failed to register GATT application")
            return
        }

        isActive = true
        logInfo(TAG, "GATT server started")
    }

    override suspend fun stopAdvertising() {
        if (!isActive) {
            return
        }

        logInfo(TAG, "Stopping GATT server...")

        unregisterGattApplication()
        closeDbusConnection()

        isActive = false
        connectedClients.clear()
        reassemblyBuffers.clear()
        logInfo(TAG, "GATT server stopped")
    }

    override suspend fun onCharacteristicWriteRequest(data: ByteArray, deviceAddress: String) {
        handleIncomingData(data, deviceAddress)
    }

    override suspend fun notifyCharacteristic(deviceAddress: String, data: ByteArray): Boolean {
        if (!isActive) {
            logError(TAG, "Cannot notify - server not active")
            return false
        }

        if (!connectedClients.isConnected(deviceAddress)) {
            logError(TAG, "Notify to ${deviceAddress.take(8)} failed: no live link to that client")
            return false
        }

        return emitNotification(deviceAddress.take(8), data)
    }

    /**
     * Emit one notification to every subscribed central.
     *
     * The `PropertiesChanged` signal carries the characteristic's object path, which names no
     * device, so a single emission reaches every subscriber. Emitting it once per registry entry
     * therefore sent N copies of every packet to everyone, which is where the duplicated
     * `Packet received` lines in the device journal came from.
     *
     * @return false when there is no live link at all, so a caller can tell a delivered broadcast
     *   from one that went nowhere.
     */
    suspend fun notifySubscribers(data: ByteArray): Boolean {
        if (!isActive) {
            logError(TAG, "Cannot notify - server not active")
            return false
        }

        val clientCount = connectedClients.size()
        if (clientCount == 0) {
            logError(TAG, "Notify of ${data.size}B failed: no client holds a live link")
            return false
        }

        return emitNotification("$clientCount client(s)", data)
    }

    private suspend fun emitNotification(target: String, data: ByteArray): Boolean {
        // Use chunking for large payloads
        return if (data.size > MAX_CHUNK_SIZE) {
            notifyChunked(target, data)
        } else {
            notifySingle(target, data)
        }
    }

    /**
     * Emit the characteristic's value as a `PropertiesChanged` signal.
     *
     * [target] is a label for the log only: the signal is addressed to the characteristic's object
     * path, which is device-agnostic, so this reaches every subscribed central regardless.
     */
    private fun notifySingle(target: String, data: ByteArray): Boolean {
        logDebug(TAG, "Notify ${data.size}B to $target")

        val connection = dbusConnection ?: return false

        return memScoped {
            // Emit PropertiesChanged signal for the characteristic
            val signal = dbus_message_new_signal(
                CHAR_PATH,
                "org.freedesktop.DBus.Properties",
                "PropertiesChanged"
            )

            if (signal == null) {
                logError(TAG, "Failed to create PropertiesChanged signal")
                return@memScoped false
            }

            // Build the signal arguments
            // interface_name, changed_properties dict, invalidated_properties array
            val iter = alloc<DBusMessageIter>()
            dbus_message_iter_init_append(signal, iter.ptr)

            // Interface name - D-Bus expects char**
            val ifaceNameStr = GATT_CHAR_IFACE.cstr.ptr
            val ifaceName = alloc<CPointerVar<ByteVar>>()
            ifaceName.value = ifaceNameStr
            dbus_message_iter_append_basic(iter.ptr, DBUS_TYPE_STRING.toInt(), ifaceName.ptr)

            // Changed properties (dict with "Value" -> byte array)
            val dictIter = alloc<DBusMessageIter>()
            dbus_message_iter_open_container(
                iter.ptr,
                DBUS_TYPE_ARRAY.toInt(),
                "{sv}",
                dictIter.ptr
            )

            // Add "Value" entry
            val entryIter = alloc<DBusMessageIter>()
            dbus_message_iter_open_container(dictIter.ptr, DBUS_TYPE_DICT_ENTRY.toInt(), null, entryIter.ptr)

            val valueNameStr = "Value".cstr.ptr
            val valueName = alloc<CPointerVar<ByteVar>>()
            valueName.value = valueNameStr
            dbus_message_iter_append_basic(entryIter.ptr, DBUS_TYPE_STRING.toInt(), valueName.ptr)

            // Variant containing byte array
            val variantIter = alloc<DBusMessageIter>()
            dbus_message_iter_open_container(entryIter.ptr, DBUS_TYPE_VARIANT.toInt(), "ay", variantIter.ptr)

            val arrayIter = alloc<DBusMessageIter>()
            dbus_message_iter_open_container(variantIter.ptr, DBUS_TYPE_ARRAY.toInt(), "y", arrayIter.ptr)

            // Append data bytes
            data.usePinned { pinned ->
                for (i in data.indices) {
                    val bytePtr = pinned.addressOf(i)
                    dbus_message_iter_append_basic(arrayIter.ptr, DBUS_TYPE_BYTE.toInt(), bytePtr)
                }
            }

            dbus_message_iter_close_container(variantIter.ptr, arrayIter.ptr)
            dbus_message_iter_close_container(entryIter.ptr, variantIter.ptr)
            dbus_message_iter_close_container(dictIter.ptr, entryIter.ptr)
            dbus_message_iter_close_container(iter.ptr, dictIter.ptr)

            // Empty invalidated properties array
            val invalidatedIter = alloc<DBusMessageIter>()
            dbus_message_iter_open_container(iter.ptr, DBUS_TYPE_ARRAY.toInt(), "s", invalidatedIter.ptr)
            dbus_message_iter_close_container(iter.ptr, invalidatedIter.ptr)

            // Send the signal
            val result = dbus_connection_send(connection, signal, null)
            dbus_message_unref(signal)

            if (result == 0u) {
                logError(TAG, "Failed to send notification signal")
                false
            } else {
                dbus_connection_flush(connection)
                true
            }
        }
    }

    private suspend fun notifyChunked(target: String, data: ByteArray): Boolean {
        val totalSize = data.size
        var offset = 0
        var chunkNumber = 0
        val totalChunks = (totalSize + MAX_CHUNK_SIZE - 1) / MAX_CHUNK_SIZE

        logInfo(TAG, "Chunking $totalSize bytes into $totalChunks chunks for $target")

        while (offset < totalSize) {
            val remaining = totalSize - offset
            val payloadSize = minOf(remaining, MAX_CHUNK_SIZE)
            val isFirst = offset == 0
            val isLast = offset + payloadSize >= totalSize

            val chunkType: Byte = when {
                isFirst -> CHUNK_START
                isLast -> CHUNK_END
                else -> CHUNK_CONTINUE
            }

            val chunk: ByteArray = if (isFirst) {
                ByteArray(5 + payloadSize).also { arr ->
                    arr[0] = chunkType
                    arr[1] = ((totalSize shr 24) and 0xFF).toByte()
                    arr[2] = ((totalSize shr 16) and 0xFF).toByte()
                    arr[3] = ((totalSize shr 8) and 0xFF).toByte()
                    arr[4] = (totalSize and 0xFF).toByte()
                    data.copyInto(arr, 5, offset, offset + payloadSize)
                }
            } else {
                ByteArray(1 + payloadSize).also { arr ->
                    arr[0] = chunkType
                    data.copyInto(arr, 1, offset, offset + payloadSize)
                }
            }

            if (!notifySingle(target, chunk)) {
                logError(TAG, "Failed to notify chunk ${chunkNumber + 1}/$totalChunks")
                return false
            }

            chunkNumber++
            offset += payloadSize

            if (!isLast) {
                delay(CHUNK_DELAY_MS)
            }
        }

        logInfo(TAG, "Successfully notified $totalChunks chunks ($totalSize bytes) to $target")
        return true
    }

    private fun handleIncomingData(value: ByteArray, deviceAddress: String) {
        if (value.isEmpty()) return

        when (value[0]) {
            CHUNK_START -> {
                if (value.size < 6) {
                    logError(TAG, "Invalid START chunk from $deviceAddress - too short")
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
                logInfo(TAG, "Started receiving chunked data from $deviceAddress, expecting $expected bytes")
            }

            CHUNK_CONTINUE, CHUNK_END -> {
                val buffer = reassemblyBuffers[deviceAddress]
                if (buffer == null) {
                    logError(TAG, "Received chunk without START from $deviceAddress")
                    return
                }
                val payload = value.copyOfRange(1, value.size)
                buffer.data.addAll(payload.toList())

                if (value[0] == CHUNK_END) {
                    val completeData = buffer.data.toByteArray()
                    reassemblyBuffers.remove(deviceAddress)
                    if (completeData.size != buffer.expectedSize) {
                        logError(TAG, "Size mismatch: expected ${buffer.expectedSize}, got ${completeData.size}")
                    } else {
                        logInfo(TAG, "Completed chunked transfer from $deviceAddress: ${completeData.size} bytes")
                    }
                    delegate?.onDataReceived(completeData, deviceAddress)
                }
            }

            else -> delegate?.onDataReceived(value, deviceAddress)
        }
    }

    /**
     * Ask the bus to route us the two signals that say a central has gone away.
     *
     * Without them nothing ever calls [onClientDisconnected]: the registry only grew, so an
     * Android phone rotating its resolvable private address left one dead entry behind per
     * rotation and handshakes were emitted into links that had been down for a minute.
     *
     * A failure here is logged and tolerated. The GATT server is still useful without pruning --
     * that is exactly the behaviour that shipped -- and refusing to start would trade a
     * degradation for an outage.
     */
    private fun addDeviceSignalMatches(connection: CPointer<DBusConnection>) {
        if (matchConnection != null) {
            logDebug(TAG, "Device signal matches already installed")
            return
        }

        val installed = mutableListOf<String>()
        memScoped {
            listOf(DEVICE_PROPERTIES_MATCH, INTERFACES_REMOVED_MATCH).forEach { rule ->
                val error = alloc<DBusError>()
                dbus_error_init(error.ptr)
                dbus_bus_add_match(connection, rule, error.ptr)
                if (dbus_error_is_set(error.ptr) != 0u) {
                    logError(TAG, "Failed to watch device signals: ${error.message?.toKString()}")
                    dbus_error_free(error.ptr)
                } else {
                    installed.add(rule)
                }
            }
        }

        if (installed.isEmpty()) {
            logError(TAG, "No device signal match installed; stale clients will not be pruned")
            return
        }

        matchConnection = connection
        dbus_connection_flush(connection)
        logInfo(TAG, "Watching BlueZ device signals (${installed.size} match rule(s))")
    }

    /**
     * Drop the match rules, against the connection they were installed on and exactly once. The
     * field is cleared before the calls so a second teardown does nothing at all.
     */
    private fun removeDeviceSignalMatches() {
        val connection = matchConnection ?: return
        matchConnection = null

        memScoped {
            listOf(DEVICE_PROPERTIES_MATCH, INTERFACES_REMOVED_MATCH).forEach { rule ->
                val error = alloc<DBusError>()
                dbus_error_init(error.ptr)
                dbus_bus_remove_match(connection, rule, error.ptr)
                if (dbus_error_is_set(error.ptr) != 0u) {
                    logDebug(TAG, "Removing a device signal match failed: ${error.message?.toKString()}")
                    dbus_error_free(error.ptr)
                }
            }
        }
        logDebug(TAG, "Stopped watching BlueZ device signals")
    }

    private fun initDbusConnection(): Boolean {
        // dbus_bus_get() takes a reference each time it is called and closeDbusConnection()
        // only ever drops one, so re-entering here without a close would leak references
        // to the shared system bus.
        if (dbusConnection != null) {
            logDebug(TAG, "D-Bus connection already exists")
            return true
        }

        logDebug(TAG, "Initializing D-Bus connection...")

        return memScoped {
            logDebug(TAG, "Allocating D-Bus error struct...")
            val error = alloc<DBusError>()
            dbus_error_init(error.ptr)

            logDebug(TAG, "Calling dbus_bus_get(SYSTEM)...")
            val connection = dbus_bus_get(DBusBusType.DBUS_BUS_SYSTEM, error.ptr)
            logDebug(TAG, "dbus_bus_get returned: ${if (connection != null) "success" else "null"}")

            if (dbus_error_is_set(error.ptr) != 0u || connection == null) {
                val errorMsg = error.message?.toKString() ?: "Unknown error"
                logError(TAG, "D-Bus connection failed: $errorMsg")
                dbus_error_free(error.ptr)
                return@memScoped false
            }

            dbusConnection = connection
            logInfo(TAG, "D-Bus connection established and stored")
            true
        }
    }

    private fun closeDbusConnection() {
        dbusConnection?.let { connection ->
            dbus_connection_unref(connection)
            dbusConnection = null
            logDebug(TAG, "D-Bus connection closed")
        }
    }

    private fun registerGattApplication(): Boolean {
        val connection = dbusConnection ?: return false

        logDebug(TAG, "Registering GATT application...")

        // First, register D-Bus objects so BlueZ can query them
        if (!registerDbusObjects(connection)) {
            logError(TAG, "Failed to register D-Bus objects")
            return false
        }

        // Use non-blocking send with manual dispatch loop
        // This ensures our filter callbacks are properly invoked during registration
        return registerGattApplicationWithManualDispatch(connection)
    }

    /**
     * Register GATT application using non-blocking send with manual dispatch.
     * This approach gives us explicit control over message dispatching,
     * ensuring our GetManagedObjects handler can properly respond.
     */
    private fun registerGattApplicationWithManualDispatch(connection: CPointer<DBusConnection>): Boolean {
        return memScoped {
            // Call org.bluez.GattManager1.RegisterApplication
            val message = dbus_message_new_method_call(
                BLUEZ_SERVICE,
                ADAPTER_PATH,
                GATT_MANAGER_IFACE,
                "RegisterApplication"
            )

            if (message == null) {
                logError(TAG, "Failed to create RegisterApplication message")
                return@memScoped false
            }

            // Arguments: object_path, options dict
            val iter = alloc<DBusMessageIter>()
            dbus_message_iter_init_append(message, iter.ptr)

            // Application path - D-Bus expects char**
            val appPathStr = APP_PATH.cstr.ptr
            val appPath = alloc<CPointerVar<ByteVar>>()
            appPath.value = appPathStr
            dbus_message_iter_append_basic(iter.ptr, DBUS_TYPE_OBJECT_PATH.toInt(), appPath.ptr)

            // Empty options dict
            val dictIter = alloc<DBusMessageIter>()
            dbus_message_iter_open_container(iter.ptr, DBUS_TYPE_ARRAY.toInt(), "{sv}", dictIter.ptr)
            dbus_message_iter_close_container(iter.ptr, dictIter.ptr)

            logDebug(TAG, "Calling RegisterApplication on $ADAPTER_PATH (non-blocking)...")

            // Use non-blocking send with reply
            val pendingCallPtr = alloc<CPointerVar<DBusPendingCall>>()
            val sent = dbus_connection_send_with_reply(
                connection,
                message,
                pendingCallPtr.ptr,
                15000 // 15 second timeout (longer to allow for dispatching)
            )

            dbus_message_unref(message)

            if (sent == 0u || pendingCallPtr.value == null) {
                logError(TAG, "Failed to send RegisterApplication message")
                return@memScoped false
            }

            val pendingCall = pendingCallPtr.value!!

            logDebug(TAG, "RegisterApplication sent, dispatching messages...")

            // Manual dispatch loop - process messages until we get a reply
            val startTime = platform.posix.time(null)
            val timeoutSeconds = 15L

            while (!dbus_pending_call_get_completed(pendingCall).toBoolean()) {
                // Check timeout
                val elapsed = platform.posix.time(null) - startTime
                if (elapsed > timeoutSeconds) {
                    logError(TAG, "RegisterApplication timed out after ${elapsed}s")
                    dbus_pending_call_cancel(pendingCall)
                    dbus_pending_call_unref(pendingCall)
                    return@memScoped false
                }

                // Read/write with short timeout to stay responsive
                dbus_connection_read_write(connection, 100) // 100ms

                // Dispatch all pending messages (this invokes our filter callbacks)
                while (dbus_connection_dispatch(connection) == DBusDispatchStatus.DBUS_DISPATCH_DATA_REMAINS) {
                    // Keep dispatching until no more data
                }
            }

            logDebug(TAG, "RegisterApplication reply received")

            // Get the reply
            val reply = dbus_pending_call_steal_reply(pendingCall)
            dbus_pending_call_unref(pendingCall)

            if (reply == null) {
                logError(TAG, "RegisterApplication: no reply message")
                return@memScoped false
            }

            // Check for error in reply
            val replyType = dbus_message_get_type(reply)
            if (replyType == DBUS_MESSAGE_TYPE_ERROR) {
                val errorName = dbus_message_get_error_name(reply)?.toKString() ?: "Unknown"
                logError(TAG, "RegisterApplication failed: $errorName")
                dbus_message_unref(reply)
                return@memScoped false
            }

            dbus_message_unref(reply)

            isRegistered = true
            logInfo(TAG, "GATT application registered successfully")

            // Start the dispatch loop for ongoing WriteValue calls from connected clients
            startDbusDispatchLoop(connection)

            true
        }
    }

    // Helper extension for UInt to Boolean
    private fun UInt.toBoolean(): Boolean = this != 0u

    /**
     * Register D-Bus objects for the GATT application, service, and characteristic.
     * BlueZ will call GetManagedObjects on these to discover the GATT structure.
     */
    private fun registerDbusObjects(connection: CPointer<DBusConnection>): Boolean {
        logDebug(TAG, "Registering D-Bus objects...")

        // Store global reference for static callbacks
        gattServerInstance = this
        ensureGattVTableInitialized()

        // Don't exit on disconnect
        dbus_connection_set_exit_on_disconnect(connection, 0u)

        // Register object paths so BlueZ can call into our GATT tree
        val paths = listOf(APP_PATH, SERVICE_PATH, CHAR_PATH)
        val registered = mutableListOf<String>()
        paths.forEach { path ->
            val ok = dbus_connection_register_object_path(
                connection,
                path,
                gattVTable,
                null
            )
            if (ok == 0u) {
                logError(TAG, "Failed to register object path: $path")
                // Roll back the paths that did register, so a retry starts from a clean tree.
                registered.forEach { dbus_connection_unregister_object_path(connection, it) }
                return false
            }
            registered.add(path)
        }

        // Add a filter as a fallback (helps when BlueZ broadcasts without object path match)
        val filterResult = dbus_connection_add_filter(
            connection,
            gattFilterFn,
            null,
            null
        )

        if (filterResult == 0u) {
            logError(TAG, "Failed to add D-Bus message filter")
            paths.forEach { dbus_connection_unregister_object_path(connection, it) }
            return false
        }

        addDeviceSignalMatches(connection)

        objectsRegistered = true

        // NOTE: Do NOT start the dispatch loop here.
        // registerGattApplicationWithManualDispatch() pumps the connection on the calling thread
        // for the duration of RegisterApplication, so that one thread both sends the call and
        // answers the GetManagedObjects that BlueZ makes before it replies. A worker dispatching
        // the same connection at the same time would race it for those messages.
        // The long-lived loop starts AFTER RegisterApplication completes.

        logInfo(TAG, "D-Bus message filter registered for GATT objects")
        return true
    }

    /**
     * Start background thread to dispatch D-Bus messages.
     * This is required for BlueZ to call our GetManagedObjects method.
     */
    private fun startDbusDispatchLoop(connection: CPointer<DBusConnection>) {
        if (dispatchRunning.compareAndSet(0, 1)) {
            logInfo(TAG, "Starting D-Bus dispatch loop...")

            // Store references for the dispatch loop (Worker needs global access)
            dbusDispatchConnection = connection
            dbusDispatchRunning = dispatchRunning

            dispatchWorker = Worker.start(name = "DBusDispatch")
            dispatchWorker?.execute(kotlin.native.concurrent.TransferMode.SAFE, { Unit }) {
                println("[BLUEZ_SERVER] D-Bus dispatch loop started")
                platform.posix.fflush(platform.posix.stdout)

                val conn = dbusDispatchConnection
                if (conn == null) {
                    println("[BLUEZ_SERVER] ERROR: D-Bus connection is null in dispatch loop")
                    platform.posix.fflush(platform.posix.stdout)
                    return@execute
                }

                // Dispatch messages while running
                while (dbusDispatchRunning?.value == 1) {
                    // Read and dispatch pending messages (non-blocking)
                    dbus_connection_read_write(conn, 100) // 100ms timeout

                    while (dbus_connection_dispatch(conn) == DBusDispatchStatus.DBUS_DISPATCH_DATA_REMAINS) {
                        // Keep dispatching until no more data
                    }
                }

                println("[BLUEZ_SERVER] D-Bus dispatch loop stopped")
                platform.posix.fflush(platform.posix.stdout)
            }

            // Give dispatch loop time to start
            platform.posix.usleep(50_000u) // 50ms
        }
    }

    /**
     * Stop the D-Bus dispatch loop.
     */
    private fun stopDbusDispatchLoop() {
        if (dispatchRunning.compareAndSet(1, 0)) {
            logInfo(TAG, "Stopping D-Bus dispatch loop...")
            // Give time for loop to exit
            platform.posix.usleep(200_000u) // 200ms
            dispatchWorker = null
            dbusDispatchConnection = null
        }
    }

    /**
     * Unregister D-Bus objects.
     */
    private fun unregisterDbusObjects(connection: CPointer<DBusConnection>) {
        // Stop dispatch loop first
        stopDbusDispatchLoop()

        removeDeviceSignalMatches()

        if (objectsRegistered) {
            dbus_connection_unregister_object_path(connection, CHAR_PATH)
            dbus_connection_unregister_object_path(connection, SERVICE_PATH)
            dbus_connection_unregister_object_path(connection, APP_PATH)
            dbus_connection_remove_filter(
                connection,
                gattFilterFn,
                null
            )
            objectsRegistered = false
            gattServerInstance = null
            logDebug(TAG, "D-Bus objects unregistered")
        }
    }

    /**
     * Handle GetManagedObjects call - returns the GATT service tree.
     */
    internal fun handleGetManagedObjects(connection: CPointer<DBusConnection>, message: CPointer<DBusMessage>): Boolean {
        logDebug(TAG, "Handling GetManagedObjects")

        return memScoped {
            val reply = dbus_message_new_method_return(message)
            if (reply == null) {
                logError(TAG, "Failed to create GetManagedObjects reply")
                return@memScoped false
            }

            val iter = alloc<DBusMessageIter>()
            dbus_message_iter_init_append(reply, iter.ptr)

            // Return type: a{oa{sa{sv}}}
            // Dict of object_path -> Dict of interface -> Dict of property -> variant
            val objectsIter = alloc<DBusMessageIter>()
            dbus_message_iter_open_container(iter.ptr, DBUS_TYPE_ARRAY.toInt(), "{oa{sa{sv}}}", objectsIter.ptr)

            // Add service object
            addServiceToManagedObjects(objectsIter.ptr)

            // Add characteristic object
            addCharacteristicToManagedObjects(objectsIter.ptr)

            dbus_message_iter_close_container(iter.ptr, objectsIter.ptr)

            val sent = dbus_connection_send(connection, reply, null)
            dbus_message_unref(reply)

            if (sent == 0u) {
                logError(TAG, "Failed to send GetManagedObjects reply")
                false
            } else {
                dbus_connection_flush(connection)
                logDebug(TAG, "Sent GetManagedObjects reply")
                true
            }
        }
    }

    private fun MemScope.addServiceToManagedObjects(objectsIter: CPointer<DBusMessageIter>) {
        // Entry: object_path -> interfaces dict
        val entryIter = alloc<DBusMessageIter>()
        dbus_message_iter_open_container(objectsIter, DBUS_TYPE_DICT_ENTRY.toInt(), null, entryIter.ptr)

        // Object path
        val servicePathStr = SERVICE_PATH.cstr.ptr
        val servicePath = alloc<CPointerVar<ByteVar>>()
        servicePath.value = servicePathStr
        dbus_message_iter_append_basic(entryIter.ptr, DBUS_TYPE_OBJECT_PATH.toInt(), servicePath.ptr)

        // Interfaces dict: a{sa{sv}}
        val ifacesIter = alloc<DBusMessageIter>()
        dbus_message_iter_open_container(entryIter.ptr, DBUS_TYPE_ARRAY.toInt(), "{sa{sv}}", ifacesIter.ptr)

        // GattService1 interface
        val serviceIfaceIter = alloc<DBusMessageIter>()
        dbus_message_iter_open_container(ifacesIter.ptr, DBUS_TYPE_DICT_ENTRY.toInt(), null, serviceIfaceIter.ptr)

        val gattServiceIfaceStr = GATT_SERVICE_IFACE.cstr.ptr
        val gattServiceIface = alloc<CPointerVar<ByteVar>>()
        gattServiceIface.value = gattServiceIfaceStr
        dbus_message_iter_append_basic(serviceIfaceIter.ptr, DBUS_TYPE_STRING.toInt(), gattServiceIface.ptr)

        // Properties dict: a{sv}
        val propsIter = alloc<DBusMessageIter>()
        dbus_message_iter_open_container(serviceIfaceIter.ptr, DBUS_TYPE_ARRAY.toInt(), "{sv}", propsIter.ptr)

        // UUID property
        addStringProperty(propsIter.ptr, "UUID", BlueZManager.SERVICE_UUID)

        // Primary property
        addBooleanProperty(propsIter.ptr, "Primary", true)

        dbus_message_iter_close_container(serviceIfaceIter.ptr, propsIter.ptr)
        dbus_message_iter_close_container(ifacesIter.ptr, serviceIfaceIter.ptr)
        dbus_message_iter_close_container(entryIter.ptr, ifacesIter.ptr)
        dbus_message_iter_close_container(objectsIter, entryIter.ptr)
    }

    private fun MemScope.addCharacteristicToManagedObjects(objectsIter: CPointer<DBusMessageIter>) {
        // Entry: object_path -> interfaces dict
        val entryIter = alloc<DBusMessageIter>()
        dbus_message_iter_open_container(objectsIter, DBUS_TYPE_DICT_ENTRY.toInt(), null, entryIter.ptr)

        // Object path
        val charPathStr = CHAR_PATH.cstr.ptr
        val charPath = alloc<CPointerVar<ByteVar>>()
        charPath.value = charPathStr
        dbus_message_iter_append_basic(entryIter.ptr, DBUS_TYPE_OBJECT_PATH.toInt(), charPath.ptr)

        // Interfaces dict: a{sa{sv}}
        val ifacesIter = alloc<DBusMessageIter>()
        dbus_message_iter_open_container(entryIter.ptr, DBUS_TYPE_ARRAY.toInt(), "{sa{sv}}", ifacesIter.ptr)

        // GattCharacteristic1 interface
        val charIfaceIter = alloc<DBusMessageIter>()
        dbus_message_iter_open_container(ifacesIter.ptr, DBUS_TYPE_DICT_ENTRY.toInt(), null, charIfaceIter.ptr)

        val gattCharIfaceStr = GATT_CHAR_IFACE.cstr.ptr
        val gattCharIface = alloc<CPointerVar<ByteVar>>()
        gattCharIface.value = gattCharIfaceStr
        dbus_message_iter_append_basic(charIfaceIter.ptr, DBUS_TYPE_STRING.toInt(), gattCharIface.ptr)

        // Properties dict: a{sv}
        val propsIter = alloc<DBusMessageIter>()
        dbus_message_iter_open_container(charIfaceIter.ptr, DBUS_TYPE_ARRAY.toInt(), "{sv}", propsIter.ptr)

        // UUID property
        addStringProperty(propsIter.ptr, "UUID", BlueZManager.CHARACTERISTIC_UUID)

        // Service property (object path)
        addObjectPathProperty(propsIter.ptr, "Service", SERVICE_PATH)

        // Flags property (array of strings)
        addStringArrayProperty(propsIter.ptr, "Flags", listOf("read", "write", "write-without-response", "notify"))

        dbus_message_iter_close_container(charIfaceIter.ptr, propsIter.ptr)
        dbus_message_iter_close_container(ifacesIter.ptr, charIfaceIter.ptr)
        dbus_message_iter_close_container(entryIter.ptr, ifacesIter.ptr)
        dbus_message_iter_close_container(objectsIter, entryIter.ptr)
    }

    private fun MemScope.addStringProperty(propsIter: CPointer<DBusMessageIter>, name: String, value: String) {
        val propIter = alloc<DBusMessageIter>()
        dbus_message_iter_open_container(propsIter, DBUS_TYPE_DICT_ENTRY.toInt(), null, propIter.ptr)

        val nameStr = name.cstr.ptr
        val namePtr = alloc<CPointerVar<ByteVar>>()
        namePtr.value = nameStr
        dbus_message_iter_append_basic(propIter.ptr, DBUS_TYPE_STRING.toInt(), namePtr.ptr)

        val variantIter = alloc<DBusMessageIter>()
        dbus_message_iter_open_container(propIter.ptr, DBUS_TYPE_VARIANT.toInt(), "s", variantIter.ptr)

        val valueStr = value.cstr.ptr
        val valuePtr = alloc<CPointerVar<ByteVar>>()
        valuePtr.value = valueStr
        dbus_message_iter_append_basic(variantIter.ptr, DBUS_TYPE_STRING.toInt(), valuePtr.ptr)

        dbus_message_iter_close_container(propIter.ptr, variantIter.ptr)
        dbus_message_iter_close_container(propsIter, propIter.ptr)
    }

    private fun MemScope.addBooleanProperty(propsIter: CPointer<DBusMessageIter>, name: String, value: Boolean) {
        val propIter = alloc<DBusMessageIter>()
        dbus_message_iter_open_container(propsIter, DBUS_TYPE_DICT_ENTRY.toInt(), null, propIter.ptr)

        val nameStr = name.cstr.ptr
        val namePtr = alloc<CPointerVar<ByteVar>>()
        namePtr.value = nameStr
        dbus_message_iter_append_basic(propIter.ptr, DBUS_TYPE_STRING.toInt(), namePtr.ptr)

        val variantIter = alloc<DBusMessageIter>()
        dbus_message_iter_open_container(propIter.ptr, DBUS_TYPE_VARIANT.toInt(), "b", variantIter.ptr)

        val boolVal = alloc<UIntVar>()
        boolVal.value = if (value) 1u else 0u
        dbus_message_iter_append_basic(variantIter.ptr, DBUS_TYPE_BOOLEAN.toInt(), boolVal.ptr)

        dbus_message_iter_close_container(propIter.ptr, variantIter.ptr)
        dbus_message_iter_close_container(propsIter, propIter.ptr)
    }

    private fun MemScope.addObjectPathProperty(propsIter: CPointer<DBusMessageIter>, name: String, value: String) {
        val propIter = alloc<DBusMessageIter>()
        dbus_message_iter_open_container(propsIter, DBUS_TYPE_DICT_ENTRY.toInt(), null, propIter.ptr)

        val nameStr = name.cstr.ptr
        val namePtr = alloc<CPointerVar<ByteVar>>()
        namePtr.value = nameStr
        dbus_message_iter_append_basic(propIter.ptr, DBUS_TYPE_STRING.toInt(), namePtr.ptr)

        val variantIter = alloc<DBusMessageIter>()
        dbus_message_iter_open_container(propIter.ptr, DBUS_TYPE_VARIANT.toInt(), "o", variantIter.ptr)

        val valueStr = value.cstr.ptr
        val valuePtr = alloc<CPointerVar<ByteVar>>()
        valuePtr.value = valueStr
        dbus_message_iter_append_basic(variantIter.ptr, DBUS_TYPE_OBJECT_PATH.toInt(), valuePtr.ptr)

        dbus_message_iter_close_container(propIter.ptr, variantIter.ptr)
        dbus_message_iter_close_container(propsIter, propIter.ptr)
    }

    private fun MemScope.addStringArrayProperty(propsIter: CPointer<DBusMessageIter>, name: String, values: List<String>) {
        val propIter = alloc<DBusMessageIter>()
        dbus_message_iter_open_container(propsIter, DBUS_TYPE_DICT_ENTRY.toInt(), null, propIter.ptr)

        val nameStr = name.cstr.ptr
        val namePtr = alloc<CPointerVar<ByteVar>>()
        namePtr.value = nameStr
        dbus_message_iter_append_basic(propIter.ptr, DBUS_TYPE_STRING.toInt(), namePtr.ptr)

        val variantIter = alloc<DBusMessageIter>()
        dbus_message_iter_open_container(propIter.ptr, DBUS_TYPE_VARIANT.toInt(), "as", variantIter.ptr)

        val arrayIter = alloc<DBusMessageIter>()
        dbus_message_iter_open_container(variantIter.ptr, DBUS_TYPE_ARRAY.toInt(), "s", arrayIter.ptr)

        for (v in values) {
            val vStr = v.cstr.ptr
            val vPtr = alloc<CPointerVar<ByteVar>>()
            vPtr.value = vStr
            dbus_message_iter_append_basic(arrayIter.ptr, DBUS_TYPE_STRING.toInt(), vPtr.ptr)
        }

        dbus_message_iter_close_container(variantIter.ptr, arrayIter.ptr)
        dbus_message_iter_close_container(propIter.ptr, variantIter.ptr)
        dbus_message_iter_close_container(propsIter, propIter.ptr)
    }

    /**
     * Handle Introspect call - returns XML describing the object.
     */
    internal fun handleIntrospect(connection: CPointer<DBusConnection>, message: CPointer<DBusMessage>, path: String): Boolean {
        val xml = when (path) {
            APP_PATH -> APP_INTROSPECT_XML
            SERVICE_PATH -> SERVICE_INTROSPECT_XML
            CHAR_PATH -> CHAR_INTROSPECT_XML
            else -> return false
        }

        return memScoped {
            val reply = dbus_message_new_method_return(message)
            if (reply == null) return@memScoped false

            val iter = alloc<DBusMessageIter>()
            dbus_message_iter_init_append(reply, iter.ptr)

            val xmlStr = xml.cstr.ptr
            val xmlPtr = alloc<CPointerVar<ByteVar>>()
            xmlPtr.value = xmlStr
            dbus_message_iter_append_basic(iter.ptr, DBUS_TYPE_STRING.toInt(), xmlPtr.ptr)

            val sent = dbus_connection_send(connection, reply, null)
            dbus_message_unref(reply)

            if (sent != 0u) {
                dbus_connection_flush(connection)
                true
            } else {
                false
            }
        }
    }

    /**
     * Handle WriteValue call on the characteristic.
     */
    internal fun handleWriteValue(connection: CPointer<DBusConnection>, message: CPointer<DBusMessage>): Boolean {
        return memScoped {
            val iter = alloc<DBusMessageIter>()
            if (dbus_message_iter_init(message, iter.ptr) == 0u) {
                logError(TAG, "WriteValue: No arguments")
                return@memScoped sendErrorReply(connection, message, "org.bluez.Error.InvalidArguments", "No arguments")
            }

            // First argument is byte array
            if (dbus_message_iter_get_arg_type(iter.ptr) != DBUS_TYPE_ARRAY.toInt()) {
                logError(TAG, "WriteValue: Expected array argument")
                return@memScoped sendErrorReply(connection, message, "org.bluez.Error.InvalidArguments", "Expected byte array")
            }

            val arrayIter = alloc<DBusMessageIter>()
            dbus_message_iter_recurse(iter.ptr, arrayIter.ptr)

            // Read bytes
            val bytes = mutableListOf<Byte>()
            while (dbus_message_iter_get_arg_type(arrayIter.ptr) == DBUS_TYPE_BYTE.toInt()) {
                val byteVal = alloc<UByteVar>()
                dbus_message_iter_get_basic(arrayIter.ptr, byteVal.ptr)
                bytes.add(byteVal.value.toByte())
                dbus_message_iter_next(arrayIter.ptr)
            }

            // Move to options dict to get device address
            dbus_message_iter_next(iter.ptr)
            var deviceAddress = "unknown"

            if (dbus_message_iter_get_arg_type(iter.ptr) == DBUS_TYPE_ARRAY.toInt()) {
                val optionsIter = alloc<DBusMessageIter>()
                dbus_message_iter_recurse(iter.ptr, optionsIter.ptr)

                while (dbus_message_iter_get_arg_type(optionsIter.ptr) == DBUS_TYPE_DICT_ENTRY.toInt()) {
                    val entryIter = alloc<DBusMessageIter>()
                    dbus_message_iter_recurse(optionsIter.ptr, entryIter.ptr)

                    if (dbus_message_iter_get_arg_type(entryIter.ptr) == DBUS_TYPE_STRING.toInt()) {
                        val keyPtr = alloc<CPointerVar<ByteVar>>()
                        dbus_message_iter_get_basic(entryIter.ptr, keyPtr.ptr)
                        val key = keyPtr.value?.toKString() ?: ""

                        if (key == "device") {
                            dbus_message_iter_next(entryIter.ptr)
                            if (dbus_message_iter_get_arg_type(entryIter.ptr) == DBUS_TYPE_VARIANT.toInt()) {
                                val variantIter = alloc<DBusMessageIter>()
                                dbus_message_iter_recurse(entryIter.ptr, variantIter.ptr)
                                if (dbus_message_iter_get_arg_type(variantIter.ptr) == DBUS_TYPE_OBJECT_PATH.toInt()) {
                                    val pathPtr = alloc<CPointerVar<ByteVar>>()
                                    dbus_message_iter_get_basic(variantIter.ptr, pathPtr.ptr)
                                    val path = pathPtr.value?.toKString() ?: ""
                                    // Extract MAC from path like /org/bluez/hci0/dev_XX_XX_XX_XX_XX_XX
                                    deviceAddress = BlueZObjectPath.deviceAddress(path) ?: deviceAddress
                                }
                            }
                        }
                    }
                    dbus_message_iter_next(optionsIter.ptr)
                }
            }

            val data = bytes.toByteArray()
            logInfo(TAG, "WriteValue: ${data.size}B from $deviceAddress")

            // Track client connection
            onClientConnected(deviceAddress)

            // Handle the incoming data
            handleIncomingData(data, deviceAddress)

            // Send success reply
            val reply = dbus_message_new_method_return(message)
            if (reply != null) {
                dbus_connection_send(connection, reply, null)
                dbus_message_unref(reply)
                dbus_connection_flush(connection)
            }

            true
        }
    }

    /**
     * Handle ReadValue call on the characteristic.
     */
    internal fun handleReadValue(connection: CPointer<DBusConnection>, message: CPointer<DBusMessage>): Boolean {
        return memScoped {
            val reply = dbus_message_new_method_return(message)
            if (reply == null) return@memScoped false

            val iter = alloc<DBusMessageIter>()
            dbus_message_iter_init_append(reply, iter.ptr)

            // Return empty byte array (or could return last notification data)
            val arrayIter = alloc<DBusMessageIter>()
            dbus_message_iter_open_container(iter.ptr, DBUS_TYPE_ARRAY.toInt(), "y", arrayIter.ptr)
            dbus_message_iter_close_container(iter.ptr, arrayIter.ptr)

            dbus_connection_send(connection, reply, null)
            dbus_message_unref(reply)
            dbus_connection_flush(connection)
            true
        }
    }

    /**
     * Handle StartNotify/StopNotify calls.
     */
    internal fun handleStartNotify(connection: CPointer<DBusConnection>, message: CPointer<DBusMessage>): Boolean {
        logDebug(TAG, "StartNotify called")
        return sendEmptyReply(connection, message)
    }

    internal fun handleStopNotify(connection: CPointer<DBusConnection>, message: CPointer<DBusMessage>): Boolean {
        logDebug(TAG, "StopNotify called")
        return sendEmptyReply(connection, message)
    }

    /**
     * Handle GetAll properties call.
     */
    internal fun handleGetAllProperties(connection: CPointer<DBusConnection>, message: CPointer<DBusMessage>, path: String): Boolean {
        return memScoped {
            val iter = alloc<DBusMessageIter>()
            if (dbus_message_iter_init(message, iter.ptr) == 0u) {
                return@memScoped sendErrorReply(connection, message, "org.bluez.Error.InvalidArguments", "No interface specified")
            }

            val ifacePtr = alloc<CPointerVar<ByteVar>>()
            dbus_message_iter_get_basic(iter.ptr, ifacePtr.ptr)
            val iface = ifacePtr.value?.toKString() ?: ""

            val reply = dbus_message_new_method_return(message)
            if (reply == null) return@memScoped false

            val replyIter = alloc<DBusMessageIter>()
            dbus_message_iter_init_append(reply, replyIter.ptr)

            val propsIter = alloc<DBusMessageIter>()
            dbus_message_iter_open_container(replyIter.ptr, DBUS_TYPE_ARRAY.toInt(), "{sv}", propsIter.ptr)

            when {
                path == SERVICE_PATH && iface == GATT_SERVICE_IFACE -> {
                    addStringProperty(propsIter.ptr, "UUID", BlueZManager.SERVICE_UUID)
                    addBooleanProperty(propsIter.ptr, "Primary", true)
                }
                path == CHAR_PATH && iface == GATT_CHAR_IFACE -> {
                    addStringProperty(propsIter.ptr, "UUID", BlueZManager.CHARACTERISTIC_UUID)
                    addObjectPathProperty(propsIter.ptr, "Service", SERVICE_PATH)
                    addStringArrayProperty(propsIter.ptr, "Flags", listOf("read", "write", "write-without-response", "notify"))
                }
            }

            dbus_message_iter_close_container(replyIter.ptr, propsIter.ptr)

            dbus_connection_send(connection, reply, null)
            dbus_message_unref(reply)
            dbus_connection_flush(connection)
            true
        }
    }

    private fun sendEmptyReply(connection: CPointer<DBusConnection>, message: CPointer<DBusMessage>): Boolean {
        val reply = dbus_message_new_method_return(message) ?: return false
        dbus_connection_send(connection, reply, null)
        dbus_message_unref(reply)
        dbus_connection_flush(connection)
        return true
    }

    private fun MemScope.sendErrorReply(connection: CPointer<DBusConnection>, message: CPointer<DBusMessage>, errorName: String, errorMessage: String): Boolean {
        val reply = dbus_message_new_error(message, errorName, errorMessage)
        if (reply != null) {
            dbus_connection_send(connection, reply, null)
            dbus_message_unref(reply)
            dbus_connection_flush(connection)
        }
        return false
    }

    private fun unregisterGattApplication() {
        val connection = dbusConnection ?: return

        if (isRegistered) {
            logDebug(TAG, "Unregistering GATT application...")

            memScoped {
                val message = dbus_message_new_method_call(
                    BLUEZ_SERVICE,
                    ADAPTER_PATH,
                    GATT_MANAGER_IFACE,
                    "UnregisterApplication"
                )

                if (message != null) {
                    val iter = alloc<DBusMessageIter>()
                    dbus_message_iter_init_append(message, iter.ptr)

                    val appPathStr = APP_PATH.cstr.ptr
                    val appPath = alloc<CPointerVar<ByteVar>>()
                    appPath.value = appPathStr
                    dbus_message_iter_append_basic(iter.ptr, DBUS_TYPE_OBJECT_PATH.toInt(), appPath.ptr)

                    dbus_connection_send(connection, message, null)
                    dbus_connection_flush(connection)
                    dbus_message_unref(message)
                }
            }

            isRegistered = false
            logInfo(TAG, "GATT application unregistered")
        }

        // Unregister D-Bus objects
        unregisterDbusObjects(connection)
    }

    /**
     * Called when a client connects (from advertising service or connection tracking).
     */
    internal fun onClientConnected(deviceAddress: String) {
        if (connectedClients.onConnected(deviceAddress)) {
            logInfo(TAG, "Client connected: $deviceAddress")
            delegate?.onClientConnected(deviceAddress)
        }
    }

    /**
     * BlueZ says the link to [deviceAddress] is gone, whichever side opened it.
     *
     * Reaps the server-side client and hands the address to the central side as well: a link we
     * dialled out on is invisible to [connectedClients], and gattlib's disconnect callback does not
     * always fire for one, which used to leave it in the registry forever.
     */
    /**
     * BlueZ says it now holds a link to [deviceAddress].
     *
     * BlueZ only announces a device the first time it appears, so a peer it was already connected
     * to when we started is never offered by the scanner and stays invisible to the mesh. Measured
     * on the Pi: BlueZ held two phones while the app counted one, and the unseen one got none of
     * our traffic.
     */
    internal fun onDeviceAppeared(deviceAddress: String) {
        onDeviceConnected?.invoke(deviceAddress)
    }

    internal fun onDeviceGone(deviceAddress: String) {
        onClientDisconnected(deviceAddress)
        onDeviceLinkGone?.invoke(deviceAddress)
    }

    /**
     * Called when a client disconnects.
     */
    internal fun onClientDisconnected(deviceAddress: String) {
        if (connectedClients.onDisconnected(deviceAddress)) {
            logInfo(TAG, "Client disconnected: $deviceAddress")
            reassemblyBuffers.remove(deviceAddress)
            delegate?.onClientDisconnected(deviceAddress)
        }
    }

    /**
     * Get list of connected client addresses.
     */
    fun getConnectedClients(): Set<String> = connectedClients.addresses()

    /** True while [deviceAddress] holds a link to this server. */
    fun isClientConnected(deviceAddress: String): Boolean = connectedClients.isConnected(deviceAddress)

    /** True when at least one central holds a link, i.e. there is anything a signal could prune. */
    internal fun hasConnectedClients(): Boolean = !connectedClients.isEmpty()

    /** Which of [addresses] still hold a link here, and which are stale entries to drop. */
    fun partitionBroadcastTargets(addresses: List<String>): BroadcastTargets =
        connectedClients.partitionTargets(addresses)
}

/**
 * Static D-Bus message filter callback.
 * Routes method calls to the GATT server instance.
 */
@OptIn(ExperimentalForeignApi::class)
private fun dbusMessageFilter(
    connection: CPointer<DBusConnection>?,
    message: CPointer<DBusMessage>?,
    userData: COpaquePointer?
): DBusHandlerResult {
    if (connection == null || message == null) {
        return DBusHandlerResult.DBUS_HANDLER_RESULT_NOT_YET_HANDLED
    }

    val server = gattServerInstance ?: return DBusHandlerResult.DBUS_HANDLER_RESULT_NOT_YET_HANDLED

    // Signals are observed, never consumed: other filters on this shared system-bus connection
    // (the advertising service's, for one) must still see them.
    if (dbus_message_get_type(message) == DBUS_MESSAGE_TYPE_SIGNAL) {
        observeDeviceSignal(message, server)
        return DBusHandlerResult.DBUS_HANDLER_RESULT_NOT_YET_HANDLED
    }

    // Only handle method calls
    if (dbus_message_get_type(message) != DBUS_MESSAGE_TYPE_METHOD_CALL) {
        return DBusHandlerResult.DBUS_HANDLER_RESULT_NOT_YET_HANDLED
    }

    val path = dbus_message_get_path(message)?.toKString() ?: return DBusHandlerResult.DBUS_HANDLER_RESULT_NOT_YET_HANDLED
    val iface = dbus_message_get_interface(message)?.toKString() ?: ""
    val member = dbus_message_get_member(message)?.toKString() ?: return DBusHandlerResult.DBUS_HANDLER_RESULT_NOT_YET_HANDLED

    // Check if this is for our GATT objects
    if (!path.startsWith("/org/bitchat/gatt")) {
        return DBusHandlerResult.DBUS_HANDLER_RESULT_NOT_YET_HANDLED
    }

    println("[BLUEZ_SERVER] D-Bus call: $path.$iface.$member")
    platform.posix.fflush(platform.posix.stdout)

    val handled = when {
        // ObjectManager.GetManagedObjects on app path
        path == "/org/bitchat/gatt" && member == "GetManagedObjects" -> {
            server.handleGetManagedObjects(connection, message)
        }

        // Introspectable.Introspect
        member == "Introspect" && iface == "org.freedesktop.DBus.Introspectable" -> {
            server.handleIntrospect(connection, message, path)
        }

        // Properties.GetAll
        member == "GetAll" && iface == "org.freedesktop.DBus.Properties" -> {
            server.handleGetAllProperties(connection, message, path)
        }

        // GattCharacteristic1 methods
        path == "/org/bitchat/gatt/service0/char0" -> when (member) {
            "WriteValue" -> server.handleWriteValue(connection, message)
            "ReadValue" -> server.handleReadValue(connection, message)
            "StartNotify" -> server.handleStartNotify(connection, message)
            "StopNotify" -> server.handleStopNotify(connection, message)
            else -> false
        }

        else -> false
    }

    return if (handled) {
        DBusHandlerResult.DBUS_HANDLER_RESULT_HANDLED
    } else {
        DBusHandlerResult.DBUS_HANDLER_RESULT_NOT_YET_HANDLED
    }
}

/**
 * Turn a BlueZ signal that says a central went away into [BlueZGattServerService.onClientDisconnected].
 *
 * Two shapes count, and BlueZ emits them in either order depending on why the link ended:
 *  - `org.bluez.Device1` reporting `Connected = false` for a device we hold, and
 *  - the device object being dropped entirely (`InterfacesRemoved` naming `org.bluez.Device1`),
 *    which is what an Android phone rotating its resolvable private address looks like.
 *
 * Dropping a client twice is harmless: the registry reports whether anything was removed and only
 * then tells the delegate.
 */
@OptIn(ExperimentalForeignApi::class)
private fun observeDeviceSignal(message: CPointer<DBusMessage>, server: BlueZGattServerService) {
    // No cheap "is anything registered" shortcut here any more. It used to return before parsing
    // whenever no server client was registered, which is exactly the state a device BlueZ has
    // connected but we have not adopted leaves us in -- so the one signal that could have told us
    // about that peer was the one being dropped. readDeviceConnectedChange answers null for an RSSI
    // update after walking a short dict, which is cheap enough to run on every signal.
    val iface = dbus_message_get_interface(message)?.toKString() ?: return
    val member = dbus_message_get_member(message)?.toKString() ?: return

    when {
        iface == "org.freedesktop.DBus.Properties" && member == "PropertiesChanged" -> {
            val path = dbus_message_get_path(message)?.toKString() ?: return
            val address = BlueZObjectPath.deviceAddress(path) ?: return
            when (readDeviceConnectedChange(message)) {
                false -> server.onDeviceGone(address)
                true -> server.onDeviceAppeared(address)
                null -> Unit
            }
        }

        iface == "org.freedesktop.DBus.ObjectManager" && member == "InterfacesRemoved" -> {
            val path = readRemovedDevicePath(message) ?: return
            val address = BlueZObjectPath.deviceAddress(path) ?: return
            server.onDeviceGone(address)
        }
    }
}

/**
 * The `Connected` value carried by an `org.bluez.Device1` `PropertiesChanged`, or null when the
 * signal does not mention `Connected` at all -- which is most of them, because this match also
 * carries every RSSI update BlueZ emits while scanning.
 * Signature: `s` interface name, `a{sv}` changed properties, `as` invalidated properties.
 */
@OptIn(ExperimentalForeignApi::class)
private fun readDeviceConnectedChange(message: CPointer<DBusMessage>): Boolean? = memScoped {
    val iter = alloc<DBusMessageIter>()
    if (dbus_message_iter_init(message, iter.ptr) == 0u) return@memScoped null

    if (dbus_message_iter_get_arg_type(iter.ptr) != DBUS_TYPE_STRING.toInt()) return@memScoped null
    val ifacePtr = alloc<CPointerVar<ByteVar>>()
    dbus_message_iter_get_basic(iter.ptr, ifacePtr.ptr)
    if (ifacePtr.value?.toKString() != "org.bluez.Device1") return@memScoped null

    dbus_message_iter_next(iter.ptr)
    if (dbus_message_iter_get_arg_type(iter.ptr) != DBUS_TYPE_ARRAY.toInt()) return@memScoped null

    val changedIter = alloc<DBusMessageIter>()
    dbus_message_iter_recurse(iter.ptr, changedIter.ptr)
    while (dbus_message_iter_get_arg_type(changedIter.ptr) == DBUS_TYPE_DICT_ENTRY.toInt()) {
        val entryIter = alloc<DBusMessageIter>()
        dbus_message_iter_recurse(changedIter.ptr, entryIter.ptr)

        if (dbus_message_iter_get_arg_type(entryIter.ptr) == DBUS_TYPE_STRING.toInt()) {
            val keyPtr = alloc<CPointerVar<ByteVar>>()
            dbus_message_iter_get_basic(entryIter.ptr, keyPtr.ptr)
            if (keyPtr.value?.toKString() == "Connected") {
                dbus_message_iter_next(entryIter.ptr)
                if (dbus_message_iter_get_arg_type(entryIter.ptr) == DBUS_TYPE_VARIANT.toInt()) {
                    val variantIter = alloc<DBusMessageIter>()
                    dbus_message_iter_recurse(entryIter.ptr, variantIter.ptr)
                    if (dbus_message_iter_get_arg_type(variantIter.ptr) == DBUS_TYPE_BOOLEAN.toInt()) {
                        val connected = alloc<UIntVar>()
                        dbus_message_iter_get_basic(variantIter.ptr, connected.ptr)
                        return@memScoped connected.value != 0u
                    }
                }
            }
        }
        dbus_message_iter_next(changedIter.ptr)
    }
    null
}

/**
 * The object path of an `InterfacesRemoved` that dropped `org.bluez.Device1`, or null.
 * Signature: `o` object path, `as` the interfaces that went away.
 */
@OptIn(ExperimentalForeignApi::class)
private fun readRemovedDevicePath(message: CPointer<DBusMessage>): String? = memScoped {
    val iter = alloc<DBusMessageIter>()
    if (dbus_message_iter_init(message, iter.ptr) == 0u) return@memScoped null

    if (dbus_message_iter_get_arg_type(iter.ptr) != DBUS_TYPE_OBJECT_PATH.toInt()) return@memScoped null
    val pathPtr = alloc<CPointerVar<ByteVar>>()
    dbus_message_iter_get_basic(iter.ptr, pathPtr.ptr)
    val path = pathPtr.value?.toKString() ?: return@memScoped null

    dbus_message_iter_next(iter.ptr)
    if (dbus_message_iter_get_arg_type(iter.ptr) != DBUS_TYPE_ARRAY.toInt()) return@memScoped null

    val ifacesIter = alloc<DBusMessageIter>()
    dbus_message_iter_recurse(iter.ptr, ifacesIter.ptr)
    while (dbus_message_iter_get_arg_type(ifacesIter.ptr) == DBUS_TYPE_STRING.toInt()) {
        val ifacePtr = alloc<CPointerVar<ByteVar>>()
        dbus_message_iter_get_basic(ifacesIter.ptr, ifacePtr.ptr)
        if (ifacePtr.value?.toKString() == "org.bluez.Device1") return@memScoped path
        dbus_message_iter_next(ifacesIter.ptr)
    }
    null
}

/**
 * The C function pointer for [dbusMessageFilter], materialised exactly once.
 *
 * Every `staticCFunction(::f)` call site makes the compiler lift its own copy of `f` and emit its
 * own C bridge for it, so two call sites naming the same Kotlin function yield two different
 * pointers. libdbus matches filters by (function, user data) and calls `_dbus_abort()` when a
 * removal finds no match, so add and remove must be handed this one value, never two
 * `staticCFunction` expressions.
 */
@OptIn(ExperimentalForeignApi::class)
private val gattFilterFn = staticCFunction(::dbusMessageFilter)

@OptIn(ExperimentalForeignApi::class)
private var gattVTable: CPointer<DBusObjectPathVTable>? = null

@OptIn(ExperimentalForeignApi::class)
private fun ensureGattVTableInitialized() {
    if (gattVTable != null) return
    gattVTable = nativeHeap.alloc<DBusObjectPathVTable>().apply {
        unregister_function = null
        message_function = staticCFunction(::gattVTableHandler)
        dbus_internal_pad1 = null
        dbus_internal_pad2 = null
    }.ptr
}

@OptIn(ExperimentalForeignApi::class)
private fun gattVTableHandler(
    connection: CPointer<DBusConnection>?,
    message: CPointer<DBusMessage>?,
    userData: COpaquePointer?
): DBusHandlerResult = dbusMessageFilter(connection, message, userData)
