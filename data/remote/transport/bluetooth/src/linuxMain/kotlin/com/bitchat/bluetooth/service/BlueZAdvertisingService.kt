package com.bitchat.bluetooth.service

import cnames.structs.DBusConnection
import cnames.structs.DBusMessage
import cnames.structs.DBusPendingCall
import com.bitchat.bluetooth.manager.AdvertisementRegistration
import com.bitchat.bluetooth.protocol.logDebug
import com.bitchat.bluetooth.protocol.logError
import com.bitchat.bluetooth.protocol.logInfo
import dbus.*
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.posix.CLOCK_MONOTONIC
import platform.posix.clock_gettime
import platform.posix.getenv
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
         * How long start-up waits for BlueZ's answer to RegisterAdvertisement before it goes on.
         *
         * startServices() runs advertising, the GATT server, scanning and the announce one after
         * another, so anything spent here delays all of them. BlueZ usually answers in under a
         * tenth of a second. When it does not, the request stands and the dispatch loop takes the
         * answer whenever it comes: BlueZ answers only once the kernel has added the instance, and
         * a connect pending in the kernel holds that back for about 21 s.
         */
        private const val START_WAIT_MS = 5_000L

        /** Poll interval while start-up waits. 5 ms costs ~40 wakeups on success. */
        private const val REGISTER_POLL_INTERVAL_US = 5_000u
    }

    private val lifecycle = Mutex()
    private var dbusConnection: CPointer<DBusConnection>? = null
    private var isCurrentlyAdvertising = false

    // The RegisterAdvertisement request BlueZ has not answered yet, and what its answers mean.
    // Start-up, stop and the dispatch loop all use them, only under the lock.
    private val registrationLock = SynchronizedObject()
    private var pendingRegistration: CPointer<DBusPendingCall>? = null
    private val registration = AdvertisementRegistration()

    private var currentServiceUuid: String = DEFAULT_SERVICE_UUID
    private var currentDeviceName: String = "bitchat"
    private var dispatchWorker: Worker? = null
    private val dispatchRunning = AtomicInt(0)

    // Teardown state. libdbus aborts the process when asked to remove a filter or
    // unregister an object path it never had, so both are removed only against the
    // connection they were installed on, and only when the install actually succeeded.
    private var filterConnection: CPointer<DBusConnection>? = null
    private var objectPathConnection: CPointer<DBusConnection>? = null

    // One at a time: the mesh service launches start and stop as separate coroutines.
    override suspend fun startAdvertising(serviceUuid: String, deviceName: String) =
        lifecycle.withLock { start(serviceUuid, deviceName) }

    override suspend fun stopAdvertising() = lifecycle.withLock { stop() }

    private fun start(serviceUuid: String, deviceName: String) {
        if (isCurrentlyAdvertising) {
            logDebug(TAG, "Already advertising")
            return
        }

        // Setting BITCHAT_BLE_NO_ADVERTISE runs this node central-only.
        //
        // It exists because the kernel pauses and resumes advertising around every outbound
        // connection (`hci_le_create_conn_sync`), and on this controller the firmware refuses:
        // `Bluetooth: hci0: Opcode 0x2036 failed: -16` and `0x2039 failed: -16` appear in the
        // kernel log exactly once per connect request -- sixteen for sixteen over the hour
        // measured. Every attempt establishes a link and loses it immediately afterwards. Running
        // without an advertisement removes that pause from the picture, which is the cheapest way
        // to find out whether the peripheral role is what kills our outbound links.
        //
        // The GATT server is deliberately left running: this suppresses the advertisement, not the
        // ability to serve a central that already knows us.
        if (getenv("BITCHAT_BLE_NO_ADVERTISE") != null) {
            logInfo(TAG, "BITCHAT_BLE_NO_ADVERTISE set: staying central-only, not advertising")
            return
        }

        currentServiceUuid = serviceUuid.ifEmpty { DEFAULT_SERVICE_UUID }
        currentDeviceName = deviceName.ifEmpty { "bitchat" }

        logInfo(TAG, "Starting BLE advertising: $currentDeviceName ($currentServiceUuid)")

        if (!initDbusConnection()) {
            logError(TAG, "Failed to initialize D-Bus connection")
            return
        }
        val connection = dbusConnection ?: return

        // Start the dispatch loop first and leave it running: it owns the socket, so it is what
        // writes RegisterAdvertisement out, answers the GetAll BlueZ makes before replying, and
        // takes the answer.
        if (!startDbusDispatchLoop()) {
            logError(TAG, "Failed to register advertisement: its object could not be exported")
            return
        }

        val sent = synchronized(registrationLock) {
            val call = sendRegisterAdvertisement(connection) ?: return@synchronized false
            pendingRegistration = call
            registration.asked(monotonicMillis())
            true
        }
        if (!sent) {
            logError(TAG, "Failed to register advertisement: RegisterAdvertisement could not be sent")
            stopDbusDispatchLoop()
            return
        }

        isCurrentlyAdvertising = true
        awaitRegistration()
    }

    private fun stop() {
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

    override fun isAdvertising(): Boolean = synchronized(registrationLock) { registration.isRegistered }

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

    /**
     * Send RegisterAdvertisement and return the pending call, or null when it could not be sent.
     *
     * Non-blocking: bluetoothd reads our object's properties over GetAll before it answers, and
     * only the dispatch worker can write that answer out. No timeout of libdbus's own either: the
     * answer is BlueZ's to give, however long the kernel keeps it waiting.
     */
    private fun sendRegisterAdvertisement(connection: CPointer<DBusConnection>): CPointer<DBusPendingCall>? = memScoped {
        val message = dbus_message_new_method_call(
            BLUEZ_SERVICE,
            ADAPTER_PATH,
            LE_ADVERTISING_MANAGER_IFACE,
            "RegisterAdvertisement"
        ) ?: return@memScoped null

        // Arguments: object path (D-Bus wants a pointer to the string pointer), empty options dict.
        val iter = alloc<DBusMessageIter>()
        dbus_message_iter_init_append(message, iter.ptr)
        val advPath = alloc<CPointerVar<ByteVar>>()
        advPath.value = ADVERTISEMENT_PATH.cstr.ptr
        dbus_message_iter_append_basic(iter.ptr, DBUS_TYPE_OBJECT_PATH.toInt(), advPath.ptr)
        val dictIter = alloc<DBusMessageIter>()
        dbus_message_iter_open_container(iter.ptr, DBUS_TYPE_ARRAY.toInt(), "{sv}", dictIter.ptr)
        dbus_message_iter_close_container(iter.ptr, dictIter.ptr)

        logDebug(TAG, "Sending RegisterAdvertisement...")
        val pendingCallPtr = alloc<CPointerVar<DBusPendingCall>>()
        pendingCallPtr.value = null
        val sent = dbus_connection_send_with_reply(connection, message, pendingCallPtr.ptr, DBUS_TIMEOUT_INFINITE)
        dbus_message_unref(message)

        // send_with_reply returns TRUE with a null pending call when the connection is already
        // disconnected, so neither check alone is enough.
        if (sent == 0u) null else pendingCallPtr.value
    }

    /** Wait up to [START_WAIT_MS] for the dispatch loop to take BlueZ's answer, then go on without it. */
    private fun awaitRegistration() {
        val startMs = monotonicMillis()
        while (synchronized(registrationLock) { registration.isAsking }) {
            val nowMs = monotonicMillis()
            // A negative reading means CLOCK_MONOTONIC is unavailable: stop waiting rather than spin.
            if (startMs < 0 || nowMs < 0 || nowMs - startMs >= START_WAIT_MS) {
                if (synchronized(registrationLock) { registration.stopWaiting() }) {
                    logInfo(
                        TAG,
                        "RegisterAdvertisement has no answer after $START_WAIT_MS ms; " +
                            "going on, and taking the answer when BlueZ gives it"
                    )
                }
                return
            }
            usleep(REGISTER_POLL_INTERVAL_US)
        }
    }

    /**
     * Take BlueZ's answer to RegisterAdvertisement if it has come. The dispatch loop calls this
     * after every pass, so an answer is taken however late it is and whichever thread read it.
     */
    internal fun takeRegistrationAnswer(connection: CPointer<DBusConnection>): Unit = synchronized(registrationLock) {
        val call = pendingRegistration ?: return@synchronized
        if (dbus_pending_call_get_completed(call) == 0u) return@synchronized
        pendingRegistration = null
        val errorName = try {
            answerOf(call)
        } finally {
            dbus_pending_call_unref(call)
        }

        when (val next = registration.answered(errorName, monotonicMillis())) {
            is AdvertisementRegistration.Next.Registered -> logInfo(
                TAG,
                if (next.late) "Advertisement registered late, ${next.afterMs} ms after it was asked for"
                else "Advertisement registered in ${next.afterMs} ms"
            )

            AdvertisementRegistration.Next.AskAgain -> {
                logInfo(TAG, "RegisterAdvertisement ended without an answer ($errorName); asking again")
                pendingRegistration = sendRegisterAdvertisement(connection)
                if (pendingRegistration == null) {
                    registration.couldNotAskAgain()
                    logError(TAG, "Failed to register advertisement: RegisterAdvertisement could not be sent again")
                }
            }

            is AdvertisementRegistration.Next.GaveUp ->
                logError(TAG, "Failed to register advertisement: ${next.reason}")

            AdvertisementRegistration.Next.Ignore -> Unit
        }
    }

    /** The error name of a completed call's answer, null for a method return; no answer at all is NoReply. */
    private fun answerOf(call: CPointer<DBusPendingCall>): String? {
        val reply = dbus_pending_call_steal_reply(call) ?: return "org.freedesktop.DBus.Error.NoReply"
        return try {
            if (dbus_message_get_type(reply) == DBUS_MESSAGE_TYPE_ERROR) {
                dbus_message_get_error_name(reply)?.toKString() ?: "unknown error"
            } else {
                null
            }
        } finally {
            dbus_message_unref(reply)
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
     * False when the object could not be exported.
     */
    private fun startDbusDispatchLoop(): Boolean {
        val connection = dbusConnection ?: return false
        // expose instance to static filter
        advertisingServiceInstance = this
        ensureAdvVTableInitialized()

        if (!dispatchRunning.compareAndSet(0, 1)) return true

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
            return false
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
            return false
        }
        filterConnection = connection

        dispatchWorker = Worker.start(name = "DBusAdvDispatch")
        dispatchWorker?.execute(
            TransferMode.SAFE,
            { Triple(connection, dispatchRunning, this) }
        ) { ctx ->
            val (conn, flag, service) = ctx
            println("[BLUEZ_ADV] D-Bus dispatch loop started")
            platform.posix.fflush(platform.posix.stdout)
            while (flag.value == 1) {
                dbus_connection_read_write(conn, 100) // 100 ms
                // After every message, not once the queue is empty: with the GATT server's
                // dispatcher reading too, the queue need never be empty.
                while (dbus_connection_dispatch(conn) == DBusDispatchStatus.DBUS_DISPATCH_DATA_REMAINS) {
                    service.takeRegistrationAnswer(conn)
                }
                service.takeRegistrationAnswer(conn)
            }
            println("[BLUEZ_ADV] D-Bus dispatch loop stopped")
            platform.posix.fflush(platform.posix.stdout)
        }

        // small delay to let loop spin up
        usleep(50_000u)
        return true
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
     * Give back what start-up asked BlueZ for: cancel a request it has not answered and, whenever
     * anything was asked for, send UnregisterAdvertisement fire-and-forget. BlueZ answering
     * DoesNotExist for one it never held costs nothing; skipping it would leave the instance
     * registered for the life of the connection.
     */
    private fun unregisterAdvertisement() {
        val unregister = synchronized(registrationLock) {
            pendingRegistration?.let { call ->
                pendingRegistration = null
                // Cancel before unref, or libdbus keeps the reply slot alive on the connection.
                dbus_pending_call_cancel(call)
                dbus_pending_call_unref(call)
            }
            registration.stop()
        }
        val connection = dbusConnection
        if (!unregister || connection == null) return

        logDebug(TAG, "Unregistering advertisement...")
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
