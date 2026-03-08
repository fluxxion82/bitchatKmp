package com.bitchat.bluetooth.service

import cnames.structs.DBusConnection
import cnames.structs.DBusMessage
import com.bitchat.bluetooth.protocol.logDebug
import com.bitchat.bluetooth.protocol.logError
import com.bitchat.bluetooth.protocol.logInfo
import dbus.*
import kotlinx.cinterop.*
import platform.posix.usleep
import kotlin.concurrent.AtomicInt
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker

/**
 * BlueZ BLE Advertising Service using D-Bus LEAdvertisingManager1.
 *
 * Implements Peripheral role advertising to make this device discoverable
 * by Central devices (iOS/Android bitchat clients).
 *
 * Uses the org.bluez.LEAdvertisingManager1 D-Bus interface.
 */
@OptIn(ExperimentalForeignApi::class)
class BlueZAdvertisingService(
    private val manager: BlueZManager
) : AdvertisingService {

    companion object {
        private const val TAG = "BLUEZ_ADV"

        // D-Bus paths and interfaces
        private const val BLUEZ_SERVICE = "org.bluez"
        private const val LE_ADVERTISING_MANAGER_IFACE = "org.bluez.LEAdvertisingManager1"
        private const val LE_ADVERTISEMENT_IFACE = "org.bluez.LEAdvertisement1"
        private const val ADAPTER_PATH = "/org/bluez/hci0"
        private const val ADVERTISEMENT_PATH = "/org/bitchat/advertisement0"

        private const val DEFAULT_SERVICE_UUID = "F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C"
    }

    private var dbusConnection: CPointer<DBusConnection>? = null
    private var isCurrentlyAdvertising = false
    private var isRegistered = false
    private var currentServiceUuid: String = DEFAULT_SERVICE_UUID
    private var currentDeviceName: String = "bitchat"
    private var dispatchWorker: Worker? = null
    private val dispatchRunning = AtomicInt(0)

    override suspend fun startAdvertising(serviceUuid: String, deviceName: String) {
        if (isCurrentlyAdvertising) {
            logDebug(TAG, "Already advertising")
            return
        }

        currentServiceUuid = serviceUuid.ifEmpty { DEFAULT_SERVICE_UUID }
        currentDeviceName = deviceName.ifEmpty { "bitchat" }

        logInfo(TAG, "Starting BLE advertising: $currentDeviceName ($currentServiceUuid)")

        if (!initDbusConnection()) {
            logError(TAG, "Failed to initialize D-Bus connection")
            return
        }

        // Start dispatch loop so BlueZ can call into our advertisement object during registration
        startDbusDispatchLoop()

        if (!registerAdvertisement()) {
            logError(TAG, "Failed to register advertisement")
            stopDbusDispatchLoop()
            return
        }

        isCurrentlyAdvertising = true
        logInfo(TAG, "Advertising started")
    }

    override suspend fun stopAdvertising() {
        if (!isCurrentlyAdvertising) {
            return
        }

        logInfo(TAG, "Stopping BLE advertising...")

        unregisterAdvertisement()
        stopDbusDispatchLoop()
        closeDbusConnection()

        isCurrentlyAdvertising = false
        logInfo(TAG, "Advertising stopped")
    }

    override fun isAdvertising(): Boolean = isCurrentlyAdvertising

    private fun initDbusConnection(): Boolean {
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
            logDebug(TAG, "D-Bus connection established and stored")
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

    private fun registerAdvertisement(): Boolean {
        val connection = dbusConnection ?: return false

        logDebug(TAG, "Registering BLE advertisement...")

        return memScoped {
            logDebug(TAG, "Creating RegisterAdvertisement method call...")
            // First, we need to create the advertisement object on D-Bus
            // This requires implementing the LEAdvertisement1 interface
            // For simplicity, we'll use the RegisterAdvertisement method directly

            val message = dbus_message_new_method_call(
                BLUEZ_SERVICE,
                ADAPTER_PATH,
                LE_ADVERTISING_MANAGER_IFACE,
                "RegisterAdvertisement"
            )

            if (message == null) {
                logError(TAG, "Failed to create RegisterAdvertisement message")
                return@memScoped false
            }
            logDebug(TAG, "Method call created, building arguments...")

            // Arguments: object_path, options dict
            logDebug(TAG, "Allocating DBusMessageIter...")
            val iter = alloc<DBusMessageIter>()
            logDebug(TAG, "Calling dbus_message_iter_init_append...")
            dbus_message_iter_init_append(message, iter.ptr)
            logDebug(TAG, "Iterator initialized")

            // Advertisement path - D-Bus expects pointer TO the string pointer (char**)
            logDebug(TAG, "Getting advertisement path cstr...")
            val advPathStr = ADVERTISEMENT_PATH.cstr.ptr
            val advPath = alloc<CPointerVar<ByteVar>>()
            advPath.value = advPathStr
            logDebug(TAG, "Calling dbus_message_iter_append_basic for path...")
            dbus_message_iter_append_basic(iter.ptr, DBUS_TYPE_OBJECT_PATH.toInt(), advPath.ptr)
            logDebug(TAG, "Path appended")

            // Options dict (empty for now)
            logDebug(TAG, "Opening dict container...")
            val dictIter = alloc<DBusMessageIter>()
            dbus_message_iter_open_container(iter.ptr, DBUS_TYPE_ARRAY.toInt(), "{sv}", dictIter.ptr)
            logDebug(TAG, "Closing dict container...")
            dbus_message_iter_close_container(iter.ptr, dictIter.ptr)

            logDebug(TAG, "Arguments built, sending D-Bus call (5s timeout)...")
            // Send with timeout
            val error = alloc<DBusError>()
            dbus_error_init(error.ptr)

            val reply = dbus_connection_send_with_reply_and_block(
                connection,
                message,
                5000, // 5 second timeout
                error.ptr
            )
            logDebug(TAG, "D-Bus call returned")

            dbus_message_unref(message)

            if (dbus_error_is_set(error.ptr) != 0u) {
                val errorMsg = error.message?.toKString() ?: "Unknown error"
                // Check if it's because advertising is not supported or already registered
                if (errorMsg.contains("Already Exists") || errorMsg.contains("AlreadyExists")) {
                    logDebug(TAG, "Advertisement already registered")
                    isRegistered = true
                    dbus_error_free(error.ptr)
                    return@memScoped true
                }
                logError(TAG, "RegisterAdvertisement failed: $errorMsg")
                logError(TAG, "Note: This requires the advertisement object to be exported on D-Bus first")
                logError(TAG, "Falling back to bluetoothctl-based approach if available")
                dbus_error_free(error.ptr)

                // Try alternative approach using adapter properties
                return@memScoped tryLegacyAdvertising()
            }

            if (reply != null) {
                dbus_message_unref(reply)
            }

            isRegistered = true
            logInfo(TAG, "Advertisement registered")
            true
        }
    }

    /**
     * Start background loop to dispatch D-Bus messages so BlueZ can reach our advertisement object.
     */
    private fun startDbusDispatchLoop() {
        val connection = dbusConnection ?: return
        // expose instance to static filter
        advertisingServiceInstance = this
        ensureAdvVTableInitialized()

        if (!dispatchRunning.compareAndSet(0, 1)) return

        // Explicitly register the advertisement object path so BlueZ knows it exists
        val registered = dbus_connection_register_object_path(
            connection,
            ADVERTISEMENT_PATH,
            advVTable,
            null
        )
        if (registered == 0u) {
            logError(TAG, "Failed to register advertisement object path")
            dispatchRunning.value = 0
            advertisingServiceInstance = null
            return
        }

        val filterAdded = dbus_connection_add_filter(
            connection,
            staticCFunction(::advertisementMessageFilter),
            null,
            null
        )
        if (filterAdded == 0u) {
            logError(TAG, "Failed to add D-Bus filter for advertisement")
            dispatchRunning.value = 0
            advertisingServiceInstance = null
            return
        }

        dispatchWorker = Worker.start(name = "DBusAdvDispatch")
        dispatchWorker?.execute(
            TransferMode.SAFE,
            { Pair(connection, dispatchRunning) }
        ) { ctx ->
            val (conn, flag) = ctx
            println("[BLUEZ_ADV] D-Bus dispatch loop started")
            platform.posix.fflush(platform.posix.stdout)
            while (flag.value == 1) {
                dbus_connection_read_write(conn, 100) // 100 ms
                while (dbus_connection_dispatch(conn) == DBusDispatchStatus.DBUS_DISPATCH_DATA_REMAINS) {
                    // keep draining
                }
            }
            println("[BLUEZ_ADV] D-Bus dispatch loop stopped")
            platform.posix.fflush(platform.posix.stdout)
        }

        // small delay to let loop spin up
        usleep(50_000u)
    }

    private fun stopDbusDispatchLoop() {
        if (dispatchRunning.compareAndSet(1, 0)) {
            usleep(200_000u) // allow worker to exit
            dispatchWorker = null
            dbusConnection?.let {
                dbus_connection_unregister_object_path(it, ADVERTISEMENT_PATH)
                dbus_connection_remove_filter(it, staticCFunction(::advertisementMessageFilter), null)
            }
        }
        advertisingServiceInstance = null
    }

    /**
     * Handle BlueZ calls for our advertisement object.
     */
    @OptIn(ExperimentalForeignApi::class)
    internal fun handleAdvertisementMethod(message: CPointer<DBusMessage>): Boolean = memScoped {
        val iface = dbus_message_get_interface(message)?.toKString() ?: return@memScoped false
        val member = dbus_message_get_member(message)?.toKString() ?: return@memScoped false

        when {
            iface == "org.freedesktop.DBus.Properties" && member == "GetAll" -> {
                // args: s (interface)
                val ifaceVar = alloc<CPointerVar<ByteVar>>()
                if (dbus_message_get_args(
                        message,
                        null,
                        DBUS_TYPE_STRING.toInt(), ifaceVar.ptr,
                        DBUS_TYPE_INVALID
                    ) == 0u
                ) return@memScoped false
                val targetIface = ifaceVar.value?.toKString()
                if (targetIface != LE_ADVERTISEMENT_IFACE) return@memScoped false

                val reply = dbus_message_new_method_return(message) ?: return@memScoped false
                val iter = alloc<DBusMessageIter>()
                dbus_message_iter_init_append(reply, iter.ptr)

                val dictIter = alloc<DBusMessageIter>()
                dbus_message_iter_open_container(iter.ptr, DBUS_TYPE_ARRAY.toInt(), "{sv}", dictIter.ptr)
                addStringProperty(dictIter.ptr, "Type", "peripheral")
                addStringArrayProperty(dictIter.ptr, "ServiceUUIDs", listOf(currentServiceUuid))
                addStringProperty(dictIter.ptr, "LocalName", currentDeviceName)
                addBooleanProperty(dictIter.ptr, "Discoverable", true)
                dbus_message_iter_close_container(iter.ptr, dictIter.ptr)

                dbus_connection_send(dbusConnection, reply, null)
                dbus_message_unref(reply)
                true
            }

            iface == "org.freedesktop.DBus.Properties" && member == "Get" -> {
                val ifaceVar = alloc<CPointerVar<ByteVar>>()
                val propVar = alloc<CPointerVar<ByteVar>>()
                if (dbus_message_get_args(
                        message,
                        null,
                        DBUS_TYPE_STRING.toInt(), ifaceVar.ptr,
                        DBUS_TYPE_STRING.toInt(), propVar.ptr,
                        DBUS_TYPE_INVALID
                    ) == 0u
                ) return@memScoped false
                val targetIface = ifaceVar.value?.toKString()
                val prop = propVar.value?.toKString()
                if (targetIface != LE_ADVERTISEMENT_IFACE || prop == null) return@memScoped false

                val reply = dbus_message_new_method_return(message) ?: return@memScoped false
                val iter = alloc<DBusMessageIter>()
                dbus_message_iter_init_append(reply, iter.ptr)

                when (prop) {
                    "Type" -> addVariantString(iter.ptr, "peripheral")
                    "ServiceUUIDs" -> addVariantStringArray(iter.ptr, listOf(currentServiceUuid))
                    "LocalName" -> addVariantString(iter.ptr, currentDeviceName)
                    "Discoverable" -> addVariantBoolean(iter.ptr, true)
                    else -> {
                        dbus_message_unref(reply)
                        return@memScoped false
                    }
                }

                dbus_connection_send(dbusConnection, reply, null)
                dbus_message_unref(reply)
                true
            }

            iface == "org.freedesktop.DBus.Introspectable" && member == "Introspect" -> {
                val xml = """
                    <!DOCTYPE node PUBLIC "-//freedesktop//DTD D-BUS Object Introspection 1.0//EN" "http://www.freedesktop.org/standards/dbus/1.0/introspect.dtd">
                    <node>
                      <interface name="org.bluez.LEAdvertisement1">
                        <method name="Release"/>
                        <property name="Type" type="s" access="read"/>
                        <property name="ServiceUUIDs" type="as" access="read"/>
                        <property name="LocalName" type="s" access="read"/>
                        <property name="Discoverable" type="b" access="read"/>
                      </interface>
                      <interface name="org.freedesktop.DBus.Properties"/>
                      <interface name="org.freedesktop.DBus.Introspectable"/>
                    </node>
                """.trimIndent()
                val reply = dbus_message_new_method_return(message) ?: return@memScoped false
                dbus_message_append_args(
                    reply,
                    DBUS_TYPE_STRING.toInt(), xml.cstr.ptr,
                    DBUS_TYPE_INVALID
                )
                dbus_connection_send(dbusConnection, reply, null)
                dbus_message_unref(reply)
                true
            }

            iface == LE_ADVERTISEMENT_IFACE && member == "Release" -> {
                logInfo(TAG, "Release requested by BlueZ")
                val reply = dbus_message_new_method_return(message) ?: return@memScoped false
                dbus_connection_send(dbusConnection, reply, null)
                dbus_message_unref(reply)
                true
            }

            else -> false
        }
    }

    /**
     * Fallback advertising method using adapter Discoverable property.
     * This is a simpler approach that makes the device discoverable without
     * full LEAdvertisement1 implementation.
     */
    private fun tryLegacyAdvertising(): Boolean {
        val connection = dbusConnection ?: return false

        logDebug(TAG, "Trying legacy advertising (setting Discoverable)...")

        return memScoped {
            // Set Discoverable = true on the adapter
            val message = dbus_message_new_method_call(
                BLUEZ_SERVICE,
                ADAPTER_PATH,
                "org.freedesktop.DBus.Properties",
                "Set"
            )

            if (message == null) {
                logError(TAG, "Failed to create Set message")
                return@memScoped false
            }

            val iter = alloc<DBusMessageIter>()
            dbus_message_iter_init_append(message, iter.ptr)

            // Interface name - D-Bus expects char**
            val ifaceNameStr = "org.bluez.Adapter1".cstr.ptr
            val ifaceName = alloc<CPointerVar<ByteVar>>()
            ifaceName.value = ifaceNameStr
            dbus_message_iter_append_basic(iter.ptr, DBUS_TYPE_STRING.toInt(), ifaceName.ptr)

            // Property name - D-Bus expects char**
            val propNameStr = "Discoverable".cstr.ptr
            val propName = alloc<CPointerVar<ByteVar>>()
            propName.value = propNameStr
            dbus_message_iter_append_basic(iter.ptr, DBUS_TYPE_STRING.toInt(), propName.ptr)

            // Value (variant containing boolean true)
            val variantIter = alloc<DBusMessageIter>()
            dbus_message_iter_open_container(iter.ptr, DBUS_TYPE_VARIANT.toInt(), "b", variantIter.ptr)

            val trueVal = 1
            val truePtr = alloc<IntVar>()
            truePtr.value = trueVal
            dbus_message_iter_append_basic(variantIter.ptr, DBUS_TYPE_BOOLEAN.toInt(), truePtr.ptr)

            dbus_message_iter_close_container(iter.ptr, variantIter.ptr)

            // Send
            val error = alloc<DBusError>()
            dbus_error_init(error.ptr)

            val reply = dbus_connection_send_with_reply_and_block(
                connection,
                message,
                5000,
                error.ptr
            )

            dbus_message_unref(message)

            if (dbus_error_is_set(error.ptr) != 0u) {
                val errorMsg = error.message?.toKString() ?: "Unknown error"
                logError(TAG, "Failed to set Discoverable: $errorMsg")
                dbus_error_free(error.ptr)
                return@memScoped false
            }

            if (reply != null) {
                dbus_message_unref(reply)
            }

            // Also set Alias (device name)
            setAdapterAlias(currentDeviceName)

            logInfo(TAG, "Legacy advertising enabled (Discoverable=true)")
            true
        }
    }

    private fun setAdapterAlias(name: String) {
        val connection = dbusConnection ?: return

        memScoped {
            val message = dbus_message_new_method_call(
                BLUEZ_SERVICE,
                ADAPTER_PATH,
                "org.freedesktop.DBus.Properties",
                "Set"
            )

            if (message == null) return@memScoped

            val iter = alloc<DBusMessageIter>()
            dbus_message_iter_init_append(message, iter.ptr)

            val ifaceNameStr = "org.bluez.Adapter1".cstr.ptr
            val ifaceName = alloc<CPointerVar<ByteVar>>()
            ifaceName.value = ifaceNameStr
            dbus_message_iter_append_basic(iter.ptr, DBUS_TYPE_STRING.toInt(), ifaceName.ptr)

            val propNameStr = "Alias".cstr.ptr
            val propName = alloc<CPointerVar<ByteVar>>()
            propName.value = propNameStr
            dbus_message_iter_append_basic(iter.ptr, DBUS_TYPE_STRING.toInt(), propName.ptr)

            val variantIter = alloc<DBusMessageIter>()
            dbus_message_iter_open_container(iter.ptr, DBUS_TYPE_VARIANT.toInt(), "s", variantIter.ptr)

            val namePtrStr = name.cstr.ptr
            val namePtr = alloc<CPointerVar<ByteVar>>()
            namePtr.value = namePtrStr
            dbus_message_iter_append_basic(variantIter.ptr, DBUS_TYPE_STRING.toInt(), namePtr.ptr)

            dbus_message_iter_close_container(iter.ptr, variantIter.ptr)

            dbus_connection_send(connection, message, null)
            dbus_connection_flush(connection)
            dbus_message_unref(message)

            logDebug(TAG, "Set adapter alias to: $name")
        }
    }

    private fun unregisterAdvertisement() {
        if (!isRegistered) return

        val connection = dbusConnection ?: return

        logDebug(TAG, "Unregistering advertisement...")

        memScoped {
            // If we used full LEAdvertisement1, unregister it
            val message = dbus_message_new_method_call(
                BLUEZ_SERVICE,
                ADAPTER_PATH,
                LE_ADVERTISING_MANAGER_IFACE,
                "UnregisterAdvertisement"
            )

            if (message != null) {
                val iter = alloc<DBusMessageIter>()
                dbus_message_iter_init_append(message, iter.ptr)

                val advPathStr = ADVERTISEMENT_PATH.cstr.ptr
                val advPath = alloc<CPointerVar<ByteVar>>()
                advPath.value = advPathStr
                dbus_message_iter_append_basic(iter.ptr, DBUS_TYPE_OBJECT_PATH.toInt(), advPath.ptr)

                dbus_connection_send(connection, message, null)
                dbus_connection_flush(connection)
                dbus_message_unref(message)
            }

            // Also disable discoverable mode
            disableDiscoverable()
        }

        isRegistered = false
        logDebug(TAG, "Advertisement unregistered")
    }

    private fun disableDiscoverable() {
        val connection = dbusConnection ?: return

        memScoped {
            val message = dbus_message_new_method_call(
                BLUEZ_SERVICE,
                ADAPTER_PATH,
                "org.freedesktop.DBus.Properties",
                "Set"
            )

            if (message == null) return@memScoped

            val iter = alloc<DBusMessageIter>()
            dbus_message_iter_init_append(message, iter.ptr)

            val ifaceNameStr = "org.bluez.Adapter1".cstr.ptr
            val ifaceName = alloc<CPointerVar<ByteVar>>()
            ifaceName.value = ifaceNameStr
            dbus_message_iter_append_basic(iter.ptr, DBUS_TYPE_STRING.toInt(), ifaceName.ptr)

            val propNameStr = "Discoverable".cstr.ptr
            val propName = alloc<CPointerVar<ByteVar>>()
            propName.value = propNameStr
            dbus_message_iter_append_basic(iter.ptr, DBUS_TYPE_STRING.toInt(), propName.ptr)

            val variantIter = alloc<DBusMessageIter>()
            dbus_message_iter_open_container(iter.ptr, DBUS_TYPE_VARIANT.toInt(), "b", variantIter.ptr)

            val falseVal = 0
            val falsePtr = alloc<IntVar>()
            falsePtr.value = falseVal
            dbus_message_iter_append_basic(variantIter.ptr, DBUS_TYPE_BOOLEAN.toInt(), falsePtr.ptr)

            dbus_message_iter_close_container(iter.ptr, variantIter.ptr)

            dbus_connection_send(connection, message, null)
            dbus_connection_flush(connection)
            dbus_message_unref(message)
        }
    }
}

/**
 * Static D-Bus filter for advertisement path.
 */
@OptIn(ExperimentalForeignApi::class)
private fun advertisementMessageFilter(
    connection: CPointer<DBusConnection>?,
    message: CPointer<DBusMessage>?,
    userData: COpaquePointer?
): DBusHandlerResult {
    if (connection == null || message == null) {
        return DBusHandlerResult.DBUS_HANDLER_RESULT_NOT_YET_HANDLED
    }

    val path = dbus_message_get_path(message)?.toKString()
    if (path != "/org/bitchat/advertisement0") {
        return DBusHandlerResult.DBUS_HANDLER_RESULT_NOT_YET_HANDLED
    }

    val adv = advertisingServiceInstance ?: return DBusHandlerResult.DBUS_HANDLER_RESULT_NOT_YET_HANDLED
    val handled = adv.handleAdvertisementMethod(message)
    return if (handled) DBusHandlerResult.DBUS_HANDLER_RESULT_HANDLED else DBusHandlerResult.DBUS_HANDLER_RESULT_NOT_YET_HANDLED
}

// Keep a reference for the static filter
private var advertisingServiceInstance: BlueZAdvertisingService? = null
@OptIn(ExperimentalForeignApi::class)
private var advVTable: CPointer<DBusObjectPathVTable>? = null

@OptIn(ExperimentalForeignApi::class)
private fun ensureAdvVTableInitialized() {
    if (advVTable != null) return
    advVTable = nativeHeap.alloc<DBusObjectPathVTable>().apply {
        unregister_function = null
        message_function = staticCFunction(::advertisementVTableHandler)
        dbus_internal_pad1 = null
        dbus_internal_pad2 = null
    }.ptr
}

@OptIn(ExperimentalForeignApi::class)
private fun advertisementVTableHandler(
    connection: CPointer<DBusConnection>?,
    message: CPointer<DBusMessage>?,
    userData: COpaquePointer?
): DBusHandlerResult = advertisementMessageFilter(connection, message, userData)

// Helper builders for properties/variants
@OptIn(ExperimentalForeignApi::class)
private fun addStringProperty(iter: CPointer<DBusMessageIter>, name: String, value: String) {
    memScoped {
        val entry = alloc<DBusMessageIter>()
        dbus_message_iter_open_container(iter, DBUS_TYPE_DICT_ENTRY.toInt(), null, entry.ptr)

        val key = name.cstr.ptr
        val keyVar = alloc<CPointerVar<ByteVar>>()
        keyVar.value = key
        dbus_message_iter_append_basic(entry.ptr, DBUS_TYPE_STRING.toInt(), keyVar.ptr)

        val variant = alloc<DBusMessageIter>()
        dbus_message_iter_open_container(entry.ptr, DBUS_TYPE_VARIANT.toInt(), "s", variant.ptr)

        val valStr = value.cstr.ptr
        val valVar = alloc<CPointerVar<ByteVar>>()
        valVar.value = valStr
        dbus_message_iter_append_basic(variant.ptr, DBUS_TYPE_STRING.toInt(), valVar.ptr)

        dbus_message_iter_close_container(entry.ptr, variant.ptr)
        dbus_message_iter_close_container(iter, entry.ptr)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun addBooleanProperty(iter: CPointer<DBusMessageIter>, name: String, value: Boolean) {
    memScoped {
        val entry = alloc<DBusMessageIter>()
        dbus_message_iter_open_container(iter, DBUS_TYPE_DICT_ENTRY.toInt(), null, entry.ptr)

        val key = name.cstr.ptr
        val keyVar = alloc<CPointerVar<ByteVar>>()
        keyVar.value = key
        dbus_message_iter_append_basic(entry.ptr, DBUS_TYPE_STRING.toInt(), keyVar.ptr)

        val variant = alloc<DBusMessageIter>()
        dbus_message_iter_open_container(entry.ptr, DBUS_TYPE_VARIANT.toInt(), "b", variant.ptr)

        val boolVal = if (value) 1 else 0
        val boolPtr = alloc<IntVar>()
        boolPtr.value = boolVal
        dbus_message_iter_append_basic(variant.ptr, DBUS_TYPE_BOOLEAN.toInt(), boolPtr.ptr)

        dbus_message_iter_close_container(entry.ptr, variant.ptr)
        dbus_message_iter_close_container(iter, entry.ptr)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun addStringArrayProperty(iter: CPointer<DBusMessageIter>, name: String, values: List<String>) {
    memScoped {
        val entry = alloc<DBusMessageIter>()
        dbus_message_iter_open_container(iter, DBUS_TYPE_DICT_ENTRY.toInt(), null, entry.ptr)

        val key = name.cstr.ptr
        val keyVar = alloc<CPointerVar<ByteVar>>()
        keyVar.value = key
        dbus_message_iter_append_basic(entry.ptr, DBUS_TYPE_STRING.toInt(), keyVar.ptr)

        val variant = alloc<DBusMessageIter>()
        dbus_message_iter_open_container(entry.ptr, DBUS_TYPE_VARIANT.toInt(), "as", variant.ptr)

        val arrayIter = alloc<DBusMessageIter>()
        dbus_message_iter_open_container(variant.ptr, DBUS_TYPE_ARRAY.toInt(), "s", arrayIter.ptr)

        values.forEach { v ->
            val vStr = v.cstr.ptr
            val vVar = alloc<CPointerVar<ByteVar>>()
            vVar.value = vStr
            dbus_message_iter_append_basic(arrayIter.ptr, DBUS_TYPE_STRING.toInt(), vVar.ptr)
        }

        dbus_message_iter_close_container(variant.ptr, arrayIter.ptr)
        dbus_message_iter_close_container(entry.ptr, variant.ptr)
        dbus_message_iter_close_container(iter, entry.ptr)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun addVariantString(iter: CPointer<DBusMessageIter>, value: String) {
    memScoped {
        val variant = alloc<DBusMessageIter>()
        dbus_message_iter_open_container(iter, DBUS_TYPE_VARIANT.toInt(), "s", variant.ptr)
        val valStr = value.cstr.ptr
        val valVar = alloc<CPointerVar<ByteVar>>()
        valVar.value = valStr
        dbus_message_iter_append_basic(variant.ptr, DBUS_TYPE_STRING.toInt(), valVar.ptr)
        dbus_message_iter_close_container(iter, variant.ptr)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun addVariantBoolean(iter: CPointer<DBusMessageIter>, value: Boolean) {
    memScoped {
        val variant = alloc<DBusMessageIter>()
        dbus_message_iter_open_container(iter, DBUS_TYPE_VARIANT.toInt(), "b", variant.ptr)
        val boolVal = if (value) 1 else 0
        val boolPtr = alloc<IntVar>()
        boolPtr.value = boolVal
        dbus_message_iter_append_basic(variant.ptr, DBUS_TYPE_BOOLEAN.toInt(), boolPtr.ptr)
        dbus_message_iter_close_container(iter, variant.ptr)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun addVariantStringArray(iter: CPointer<DBusMessageIter>, values: List<String>) {
    memScoped {
        val variant = alloc<DBusMessageIter>()
        dbus_message_iter_open_container(iter, DBUS_TYPE_VARIANT.toInt(), "as", variant.ptr)

        val arrayIter = alloc<DBusMessageIter>()
        dbus_message_iter_open_container(variant.ptr, DBUS_TYPE_ARRAY.toInt(), "s", arrayIter.ptr)

        values.forEach { v ->
            val vStr = v.cstr.ptr
            val vVar = alloc<CPointerVar<ByteVar>>()
            vVar.value = vStr
            dbus_message_iter_append_basic(arrayIter.ptr, DBUS_TYPE_STRING.toInt(), vVar.ptr)
        }

        dbus_message_iter_close_container(variant.ptr, arrayIter.ptr)
        dbus_message_iter_close_container(iter, variant.ptr)
    }
}
