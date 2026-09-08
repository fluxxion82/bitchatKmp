package com.bitchat.bluetooth.service

import cnames.structs.DBusConnection
import cnames.structs.DBusMessage
import cnames.structs.DBusPendingCall
import com.bitchat.bluetooth.protocol.logDebug
import com.bitchat.bluetooth.protocol.logError
import com.bitchat.bluetooth.protocol.logInfo
import dbus.*
import kotlinx.cinterop.*
import platform.posix.CLOCK_MONOTONIC
import platform.posix.clock_gettime
import platform.posix.timespec
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

        /**
         * How long we wait for RegisterAdvertisement, both as the libdbus timeout on the pending
         * call and as our own deadline.
         *
         * Five seconds, not thirty: startServices() runs advertising, the GATT server, scanning and
         * the announce one after another in a single launch, so anything spent here delays all of
         * them. The reply now arrives in roughly the time RegisterApplication takes -- a couple of
         * hundred milliseconds -- so this is a bound on failure, not a budget.
         */
        private const val REGISTER_TIMEOUT_MS = 5_000L

        /** Poll interval while waiting for the pending call. 5 ms costs ~40 wakeups on success. */
        private const val REGISTER_POLL_INTERVAL_US = 5_000u
    }

    /**
     * How far the advertisement got with LEAdvertisingManager1.
     *
     * A single boolean cannot say what shutdown needs to know. RegisterAdvertisement is the one
     * call where BlueZ calls back into us -- it reads LEAdvertisement1's properties over GetAll --
     * before it replies, so a reply we never see tells us nothing about whether BlueZ went on to
     * register the instance. It usually did. [UNKNOWN] is that case, and it has to be torn down
     * exactly like [REGISTERED] or the instance stays registered for the life of the connection.
     */
    private enum class Registration { NONE, REGISTERED, UNKNOWN }

    private var dbusConnection: CPointer<DBusConnection>? = null
    private var isCurrentlyAdvertising = false
    private var registration = Registration.NONE
    private var usingLegacyDiscoverable = false
    private var currentServiceUuid: String = DEFAULT_SERVICE_UUID
    private var currentDeviceName: String = "bitchat"
    private var dispatchWorker: Worker? = null
    private val dispatchRunning = AtomicInt(0)

    // Teardown state. libdbus aborts the process when asked to remove a filter or
    // unregister an object path it never had, so both are removed only against the
    // connection they were installed on, and only when the install actually succeeded.
    private var filterConnection: CPointer<DBusConnection>? = null
    private var objectPathConnection: CPointer<DBusConnection>? = null

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

        // Start the dispatch loop first and leave it running: it owns the socket, so it is what
        // writes RegisterAdvertisement out, answers the GetAll BlueZ makes before replying, and
        // completes the pending call. registerAdvertisement() only waits.
        startDbusDispatchLoop()

        if (!registerAdvertisement()) {
            logError(TAG, "Failed to register advertisement")
            // Give back anything the attempt may have taken -- BlueZ can have registered the
            // instance even when we never saw the reply.
            unregisterAdvertisement()
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

            logDebug(TAG, "Arguments built, sending RegisterAdvertisement (non-blocking)...")

            // Non-blocking send. The old code used send_with_reply_and_block, which sits in poll()
            // holding the connection's I/O path for the whole timeout. RegisterAdvertisement cannot
            // complete under that: bluetoothd does Properties.GetAll on our advertisement object and
            // waits for the answer before replying, and the dispatch worker could queue that answer
            // but not write it. Every start therefore took the full 5 s, reported NO_REPLY, and fell
            // back to plain discoverability -- while BlueZ, once the reply finally went out, had
            // registered the advertisement anyway.
            val pendingCallPtr = alloc<CPointerVar<DBusPendingCall>>()
            pendingCallPtr.value = null
            val sent = dbus_connection_send_with_reply(
                connection,
                message,
                pendingCallPtr.ptr,
                REGISTER_TIMEOUT_MS.toInt()
            )

            dbus_message_unref(message)

            // send_with_reply returns TRUE with a null pending call when the connection is already
            // disconnected, so neither check alone is enough.
            val pendingCall = if (sent == 0u) null else pendingCallPtr.value
            if (pendingCall == null) {
                logError(TAG, "Failed to send RegisterAdvertisement (connection unusable)")
                return@memScoped tryLegacyAdvertising()
            }

            try {
                // Wait, do not dispatch. The dispatch worker started before us owns the socket and
                // will complete this call; dispatching from here would re-enter the connection on a
                // thread that is not the one holding the dispatch token.
                val startMs = monotonicMillis()
                var elapsedMs = 0L
                var completed = false
                while (true) {
                    if (dbus_pending_call_get_completed(pendingCall) != 0u) {
                        completed = true
                    }
                    val nowMs = monotonicMillis()
                    // A negative reading means CLOCK_MONOTONIC is unavailable; treat it as expiry
                    // rather than spinning forever.
                    elapsedMs = if (startMs < 0 || nowMs < 0) REGISTER_TIMEOUT_MS else nowMs - startMs
                    if (completed || elapsedMs >= REGISTER_TIMEOUT_MS) break
                    usleep(REGISTER_POLL_INTERVAL_US)
                }

                if (!completed) {
                    // Cancel before unref, or libdbus keeps the reply slot alive on the connection.
                    dbus_pending_call_cancel(pendingCall)
                    logError(
                        TAG,
                        "RegisterAdvertisement got no reply in ${elapsedMs} ms; " +
                            "BlueZ may have registered the advertisement anyway"
                    )
                    registration = Registration.UNKNOWN
                    return@memScoped tryLegacyAdvertising()
                }

                val reply = dbus_pending_call_steal_reply(pendingCall)
                if (reply == null) {
                    logError(TAG, "RegisterAdvertisement completed without a reply message")
                    registration = Registration.UNKNOWN
                    return@memScoped tryLegacyAdvertising()
                }

                try {
                    if (dbus_message_get_type(reply) == DBUS_MESSAGE_TYPE_ERROR) {
                        val errorName = dbus_message_get_error_name(reply)?.toKString() ?: "unknown"
                        if (errorName.contains("AlreadyExists")) {
                            logDebug(TAG, "Advertisement already registered")
                            registration = Registration.REGISTERED
                            return@memScoped true
                        }
                        if (errorName == "org.freedesktop.DBus.Error.NoReply") {
                            // libdbus expired the call itself. Same ambiguity as our own deadline.
                            logError(
                                TAG,
                                "RegisterAdvertisement timed out after ${elapsedMs} ms; " +
                                    "BlueZ may have registered the advertisement anyway"
                            )
                            registration = Registration.UNKNOWN
                            return@memScoped tryLegacyAdvertising()
                        }
                        logError(TAG, "RegisterAdvertisement failed: $errorName")
                        return@memScoped tryLegacyAdvertising()
                    }

                    registration = Registration.REGISTERED
                    logInfo(TAG, "Advertisement registered in ${elapsedMs} ms")
                    true
                } finally {
                    dbus_message_unref(reply)
                }
            } finally {
                dbus_pending_call_unref(pendingCall)
            }
        }
    }

    /**
     * Milliseconds from CLOCK_MONOTONIC, or -1 if the clock cannot be read.
     *
     * Not time(2): it is wall clock at one-second granularity, and this device is a headless Pi
     * with no RTC whose clock timesyncd steps during startup -- exactly the window this deadline
     * covers. A backwards step there would stretch the deadline arbitrarily.
     */
    private fun monotonicMillis(): Long = memScoped {
        val ts = alloc<timespec>()
        if (clock_gettime(CLOCK_MONOTONIC, ts.ptr) != 0) return@memScoped -1L
        ts.tv_sec * 1000L + ts.tv_nsec / 1_000_000L
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
        objectPathConnection = connection

        val filterAdded = dbus_connection_add_filter(
            connection,
            advertisementFilterFn,
            null,
            null
        )
        if (filterAdded == 0u) {
            logError(TAG, "Failed to add D-Bus filter for advertisement")
            dbus_connection_unregister_object_path(connection, ADVERTISEMENT_PATH)
            objectPathConnection = null
            dispatchRunning.value = 0
            advertisingServiceInstance = null
            return
        }
        filterConnection = connection

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
        }

        // Undo the registrations independently of the dispatch flag, each against the
        // connection it was made on, and each exactly once. Nulling before the call
        // makes a second teardown a no-op instead of a libdbus abort.
        objectPathConnection?.let { conn ->
            objectPathConnection = null
            dbus_connection_unregister_object_path(conn, ADVERTISEMENT_PATH)
        }
        filterConnection?.let { conn ->
            filterConnection = null
            dbus_connection_remove_filter(conn, advertisementFilterFn, null)
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

        // This path answers the callback BlueZ makes inside RegisterAdvertisement, and it used to
        // log nothing at all -- so a registration that stalled gave no clue whether the callback
        // had even arrived. One line per call is cheap; BlueZ makes a handful, not a stream.
        logDebug(TAG, "Advertisement object call: $iface.$member")

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
                dbus_connection_flush(dbusConnection)
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
                dbus_connection_flush(dbusConnection)
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
                // dbus_message_append_args takes the ADDRESS of the value for every basic type,
                // so a string argument has to be a char**, exactly as in the appends above and
                // in the matching dbus_message_get_args calls. Passing the char* itself made
                // libdbus read the first eight bytes of the XML as a pointer and dereference
                // it, which is a wild read on any introspection of the advertisement object.
                val xmlVar = alloc<CPointerVar<ByteVar>>()
                xmlVar.value = xml.cstr.ptr
                dbus_message_append_args(
                    reply,
                    DBUS_TYPE_STRING.toInt(), xmlVar.ptr,
                    DBUS_TYPE_INVALID
                )
                dbus_connection_send(dbusConnection, reply, null)
                dbus_connection_flush(dbusConnection)
                dbus_message_unref(reply)
                true
            }

            iface == LE_ADVERTISEMENT_IFACE && member == "Release" -> {
                logInfo(TAG, "Release requested by BlueZ")
                val reply = dbus_message_new_method_return(message) ?: return@memScoped false
                dbus_connection_send(dbusConnection, reply, null)
                dbus_connection_flush(dbusConnection)
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

            // Record it: this is adapter-wide state we now own and have to put back. The old flag
            // was never set here, so unregisterAdvertisement() returned early and the adapter
            // stayed discoverable after every stop.
            usingLegacyDiscoverable = true
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

    /**
     * Undo whatever the start path actually took, each half independently.
     *
     * [Registration.UNKNOWN] is unregistered like [Registration.REGISTERED]: we send
     * UnregisterAdvertisement fire-and-forget, so BlueZ answering DoesNotExist for one we never got
     * costs nothing, while skipping it would leak the instance for the life of the connection.
     * Discoverable is only put back if the legacy fallback is what set it -- it is adapter-wide
     * state and not ours to clear otherwise.
     */
    private fun unregisterAdvertisement() {
        val connection = dbusConnection
        if (connection == null) {
            registration = Registration.NONE
            usingLegacyDiscoverable = false
            return
        }

        if (registration != Registration.NONE) {
            val wasUnknown = registration == Registration.UNKNOWN
            registration = Registration.NONE
            logDebug(
                TAG,
                if (wasUnknown) "Unregistering advertisement (registration outcome was unknown)..."
                else "Unregistering advertisement..."
            )

            memScoped {
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
            }
            logDebug(TAG, "Advertisement unregistered")
        }

        if (usingLegacyDiscoverable) {
            usingLegacyDiscoverable = false
            disableDiscoverable()
            logDebug(TAG, "Legacy discoverability disabled")
        }
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

/**
 * The C function pointer for [advertisementMessageFilter], materialised exactly once.
 *
 * Every `staticCFunction(::f)` call site makes the compiler lift its own copy of `f` and emit its
 * own C bridge for it, so two call sites naming the same Kotlin function yield two different
 * pointers. libdbus matches filters by (function, user data) and calls `_dbus_abort()` when a
 * removal finds no match, so add and remove must be handed this one value, never two
 * `staticCFunction` expressions.
 */
@OptIn(ExperimentalForeignApi::class)
private val advertisementFilterFn = staticCFunction(::advertisementMessageFilter)

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
