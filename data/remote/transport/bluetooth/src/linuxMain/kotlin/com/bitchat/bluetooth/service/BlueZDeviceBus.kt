package com.bitchat.bluetooth.service

import cnames.structs.DBusConnection
import cnames.structs.DBusMessage
import com.bitchat.bluetooth.manager.BlueZDeviceInfo
import com.bitchat.bluetooth.manager.BlueZObjectPath
import com.bitchat.bluetooth.protocol.logError
import com.bitchat.bluetooth.protocol.logInfo
import dbus.*
import kotlin.concurrent.AtomicInt
import kotlinx.cinterop.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import platform.posix.getenv

/**
 * Two questions for bluetoothd that gattlib cannot put: which devices it holds, and "disconnect this
 * one" for a link this app has no gattlib connection for.
 *
 * Every session opens a connection of its own to the system bus and closes it again. The GATT
 * server and the advertising service share the connection `dbus_bus_get` hands out and each runs a
 * dispatch thread on it; a blocking round trip made on that connection from a third thread competes
 * with them for the reply. A private connection takes no part in that. It is opened with
 * `dbus_connection_open_private` and registered by sending `Hello` by hand, not with
 * `dbus_bus_get_private`: that one holds libdbus's process-wide bus lock while it waits for the
 * daemon, and a `dbus_bus_get` on another thread would wait behind it.
 *
 * libdbus cannot be made to give up in every case. A round trip is limited ([STEP_TIMEOUT_MS]), but
 * connecting to the bus socket and writing the first message to a daemon that has accepted the
 * socket and not finished authentication are not. So a session runs on a thread of its own and the
 * caller stops waiting for it after [SESSION_LIMIT_MS]. A session that has not returned by then is
 * left behind: from that moment it asks BlueZ to disconnect nothing (it may still finish listing),
 * and no other session is started until it has returned. (When the caller itself runs again is up
 * to the app's dispatcher, like everything else it does.)
 *
 * Nothing in here throws: a failure is one log line and a null or false result.
 */
@OptIn(ExperimentalForeignApi::class)
internal object BlueZDeviceBus {
    private const val TAG = "BLUEZ_BUS"
    private const val STEP_TIMEOUT_MS = 5_000
    private const val SESSION_LIMIT_MS = 15_000L
    private const val SYSTEM_BUS_ADDRESS = "unix:path=/run/dbus/system_bus_socket"

    // The one thread sessions run on, so that a caller can stop waiting for one.
    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    private val busThread by lazy { newSingleThreadContext("bluez-bus") }

    // 1 while a session is on that thread, whether or not anybody still waits for it.
    private val sessionRunning = AtomicInt(0)

    private class Answer<T>(val value: T?)

    /**
     * Runs [block] with a private system-bus connection and gives up waiting for it after
     * [SESSION_LIMIT_MS]. Null when the bus could not be reached, when the session did not return
     * in that time, or while an earlier session still has not returned.
     *
     * [block] runs on the bus thread, not on the caller's.
     */
    @OptIn(DelicateCoroutinesApi::class)
    suspend fun <T> session(block: (Session) -> T): T? {
        if (!sessionRunning.compareAndSet(0, 1)) {
            logError(TAG, "An earlier BlueZ bus session has not returned; not starting another")
            return null
        }
        val abandoned = AtomicInt(0)
        // Not a child of the caller: a caller that gives up must not then wait for this to end.
        val work = GlobalScope.async(busThread) {
            try {
                blockingSession(abandoned, block)
            } finally {
                sessionRunning.value = 0
            }
        }
        val answer = withTimeoutOrNull(SESSION_LIMIT_MS) { Answer(work.awaitOrAbandon(abandoned)) }
        if (answer == null) {
            logError(TAG, "BlueZ's bus did not finish a session within ${SESSION_LIMIT_MS}ms; leaving it behind")
            return null
        }
        return answer.value
    }

    /**
     * Wait for the session's result, and mark the session [abandoned] the moment the wait is given
     * up: by the limit, or because the caller itself was cancelled.
     *
     * The mark is set by the cancellation handler, which runs on the thread that cancels (for the
     * limit, the timer's), not when the caller next runs. That matters: the caller needs a thread
     * of the app's dispatcher to go on, and those can all be waiting in gattlib for a dial to
     * return, for as long as 25 s. The session must stop sending at the limit all the same.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun <T> Deferred<T>.awaitOrAbandon(abandoned: AtomicInt): T =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { abandoned.value = 1 }
            invokeOnCompletion { failure ->
                if (failure == null) continuation.resume(getCompleted())
                else continuation.resumeWithException(failure)
            }
        }

    private fun <T> blockingSession(abandoned: AtomicInt, block: (Session) -> T): T? = try {
        memScoped {
            val error = alloc<DBusError>()
            dbus_error_init(error.ptr)
            val address = getenv("DBUS_SYSTEM_BUS_ADDRESS")?.toKString() ?: SYSTEM_BUS_ADDRESS
            val connection = dbus_connection_open_private(address.cstr.ptr, error.ptr)
            if (connection == null || dbus_error_is_set(error.ptr) != 0u) {
                logError(TAG, "Could not open BlueZ's system bus: ${takeError(error.ptr)}")
                connection?.let {
                    dbus_connection_close(it)
                    dbus_connection_unref(it)
                }
                return@memScoped null
            }

            // Before anything else: libdbus exits the process when a connection it was told to
            // exit on is disconnected, and this one is closed below every time.
            dbus_connection_set_exit_on_disconnect(connection, 0u)
            try {
                if (!hello(connection)) return@memScoped null
                block(Session(connection, abandoned))
            } catch (t: Throwable) {
                logError(TAG, "BlueZ bus session failed: ${t.message ?: t::class.simpleName}")
                null
            } finally {
                dbus_connection_close(connection)
                dbus_connection_unref(connection)
            }
        }
    } catch (t: Throwable) {
        logError(TAG, "Could not start a BlueZ bus session: ${t.message ?: t::class.simpleName}")
        null
    }

    class Session internal constructor(
        private val connection: CPointer<DBusConnection>,
        private val abandoned: AtomicInt
    ) {
        /**
         * Every device BlueZ knows on the app's adapter, with whether it is connected; null when
         * BlueZ did not answer.
         */
        fun devices(): List<BlueZDeviceInfo>? = try {
            val reply = call(
                connection,
                destination = "org.bluez",
                path = "/",
                interfaceName = "org.freedesktop.DBus.ObjectManager",
                member = "GetManagedObjects"
            ) ?: return null
            try {
                readDevices(reply)
            } finally {
                dbus_message_unref(reply)
            }
        } catch (t: Throwable) {
            logError(TAG, "Could not read BlueZ devices: ${t.message ?: t::class.simpleName}")
            null
        }

        /**
         * Ask BlueZ to disconnect [address]; true when the request was written to the bus.
         *
         * No answer is waited for. BlueZ answers `Disconnect` only once the link is down (13 s
         * was measured while the controller was busy creating a connection), and the outcome
         * reaches the app anyway as `Connected = false`. The request is written out with a bounded
         * loop because `dbus_connection_flush` has no limit of its own. A session its caller has
         * stopped waiting for asks for no disconnect.
         */
        fun disconnect(address: String): Boolean = try {
            // The caller has stopped waiting and has gone on; what it decided when it asked for
            // this may no longer hold (at start-up the app is advertising by now).
            if (abandoned.value != 0) {
                logInfo(TAG, "Not disconnecting ${address.take(8)}: this bus session was left behind")
                return false
            }
            val path = BlueZObjectPath.devicePath(address) ?: run {
                logInfo(TAG, "Could not disconnect ${address.take(8)}: not a Bluetooth address")
                return false
            }
            memScoped {
                val message = dbus_message_new_method_call(
                    "org.bluez",
                    path,
                    "org.bluez.Device1",
                    "Disconnect"
                ) ?: run {
                    logError(TAG, "Could not create Disconnect for ${address.take(8)}")
                    return@memScoped false
                }
                try {
                    dbus_message_set_no_reply(message, 1u)
                    if (dbus_connection_send(connection, message, null) == 0u) {
                        logError(TAG, "Could not send Disconnect for ${address.take(8)}")
                        return@memScoped false
                    }
                } finally {
                    dbus_message_unref(message)
                }

                repeat(20) {
                    if (dbus_connection_has_messages_to_send(connection) == 0u) return@memScoped true
                    dbus_connection_read_write(connection, 100)
                }
                val sent = dbus_connection_has_messages_to_send(connection) == 0u
                if (!sent) logInfo(TAG, "Disconnect for ${address.take(8)} was not written before the bus deadline")
                sent
            }
        } catch (t: Throwable) {
            logError(TAG, "Could not disconnect ${address.take(8)} through BlueZ: ${t.message ?: t::class.simpleName}")
            false
        }
    }

    private fun hello(connection: CPointer<DBusConnection>): Boolean {
        val reply = call(
            connection,
            destination = "org.freedesktop.DBus",
            path = "/org/freedesktop/DBus",
            interfaceName = "org.freedesktop.DBus",
            member = "Hello"
        ) ?: return false
        dbus_message_unref(reply)
        return true
    }

    private fun call(
        connection: CPointer<DBusConnection>,
        destination: String,
        path: String,
        interfaceName: String,
        member: String
    ): CPointer<DBusMessage>? = memScoped {
        val message = dbus_message_new_method_call(destination, path, interfaceName, member) ?: run {
            logError(TAG, "Could not create BlueZ $member request")
            return@memScoped null
        }
        val error = alloc<DBusError>()
        dbus_error_init(error.ptr)
        val reply = try {
            dbus_connection_send_with_reply_and_block(connection, message, STEP_TIMEOUT_MS, error.ptr)
        } finally {
            dbus_message_unref(message)
        }
        if (dbus_error_is_set(error.ptr) != 0u) {
            logError(TAG, "BlueZ $member failed: ${takeError(error.ptr)}")
            reply?.let { dbus_message_unref(it) }
            return@memScoped null
        }
        if (reply == null) logError(TAG, "BlueZ did not answer $member")
        reply
    }

    private fun takeError(error: CPointer<DBusError>): String {
        if (dbus_error_is_set(error) == 0u) return "Unknown error"
        val message = error.pointed.message?.toKString() ?: "Unknown error"
        dbus_error_free(error)
        return message
    }

    private fun readDevices(reply: CPointer<DBusMessage>): List<BlueZDeviceInfo>? = memScoped {
        val root = alloc<DBusMessageIter>()
        if (dbus_message_iter_init(reply, root.ptr) == 0u ||
            dbus_message_iter_get_arg_type(root.ptr) != DBUS_TYPE_ARRAY.toInt()
        ) {
            logError(TAG, "BlueZ returned an unexpected device list")
            return@memScoped null
        }

        val objects = alloc<DBusMessageIter>()
        dbus_message_iter_recurse(root.ptr, objects.ptr)
        buildList {
            while (dbus_message_iter_get_arg_type(objects.ptr) == DBUS_TYPE_DICT_ENTRY.toInt()) {
                readDevice(objects.ptr)?.let(::add)
                dbus_message_iter_next(objects.ptr)
            }
        }
    }

    private fun MemScope.readDevice(entry: CPointer<DBusMessageIter>): BlueZDeviceInfo? {
        val objectEntry = alloc<DBusMessageIter>()
        dbus_message_iter_recurse(entry, objectEntry.ptr)
        if (dbus_message_iter_get_arg_type(objectEntry.ptr) != DBUS_TYPE_OBJECT_PATH.toInt()) return null
        val objectPath = alloc<CPointerVar<ByteVar>>()
        dbus_message_iter_get_basic(objectEntry.ptr, objectPath.ptr)
        val path = objectPath.value?.toKString() ?: return null
        // Only the adapter the app uses, the one disconnect() addresses and the one whose device
        // signals the app follows: see BlueZObjectPath.isOnAdapter.
        if (!BlueZObjectPath.isOnAdapter(path)) return null
        val address = BlueZObjectPath.deviceAddress(path) ?: return null

        dbus_message_iter_next(objectEntry.ptr)
        if (dbus_message_iter_get_arg_type(objectEntry.ptr) != DBUS_TYPE_ARRAY.toInt()) return null
        val interfaces = alloc<DBusMessageIter>()
        dbus_message_iter_recurse(objectEntry.ptr, interfaces.ptr)
        while (dbus_message_iter_get_arg_type(interfaces.ptr) == DBUS_TYPE_DICT_ENTRY.toInt()) {
            val interfaceEntry = alloc<DBusMessageIter>()
            dbus_message_iter_recurse(interfaces.ptr, interfaceEntry.ptr)
            if (dbus_message_iter_get_arg_type(interfaceEntry.ptr) == DBUS_TYPE_STRING.toInt()) {
                val name = alloc<CPointerVar<ByteVar>>()
                dbus_message_iter_get_basic(interfaceEntry.ptr, name.ptr)
                if (name.value?.toKString() == "org.bluez.Device1") {
                    dbus_message_iter_next(interfaceEntry.ptr)
                    val properties = readDeviceProperties(interfaceEntry.ptr)
                    return BlueZDeviceInfo(address, properties.first, properties.second)
                }
            }
            dbus_message_iter_next(interfaces.ptr)
        }
        return null
    }

    private fun MemScope.readDeviceProperties(properties: CPointer<DBusMessageIter>): Pair<Boolean, List<String>> {
        if (dbus_message_iter_get_arg_type(properties) != DBUS_TYPE_ARRAY.toInt()) return false to emptyList()
        val entries = alloc<DBusMessageIter>()
        dbus_message_iter_recurse(properties, entries.ptr)
        var connected = false
        var uuids = emptyList<String>()
        while (dbus_message_iter_get_arg_type(entries.ptr) == DBUS_TYPE_DICT_ENTRY.toInt()) {
            val entry = alloc<DBusMessageIter>()
            dbus_message_iter_recurse(entries.ptr, entry.ptr)
            if (dbus_message_iter_get_arg_type(entry.ptr) == DBUS_TYPE_STRING.toInt()) {
                val key = alloc<CPointerVar<ByteVar>>()
                dbus_message_iter_get_basic(entry.ptr, key.ptr)
                dbus_message_iter_next(entry.ptr)
                if (dbus_message_iter_get_arg_type(entry.ptr) == DBUS_TYPE_VARIANT.toInt()) {
                    val value = alloc<DBusMessageIter>()
                    dbus_message_iter_recurse(entry.ptr, value.ptr)
                    when (key.value?.toKString()) {
                        "Connected" -> if (dbus_message_iter_get_arg_type(value.ptr) == DBUS_TYPE_BOOLEAN.toInt()) {
                            val boolean = alloc<UIntVar>()
                            dbus_message_iter_get_basic(value.ptr, boolean.ptr)
                            connected = boolean.value != 0u
                        }

                        "UUIDs" -> if (dbus_message_iter_get_arg_type(value.ptr) == DBUS_TYPE_ARRAY.toInt()) {
                            val values = alloc<DBusMessageIter>()
                            dbus_message_iter_recurse(value.ptr, values.ptr)
                            val parsed = mutableListOf<String>()
                            while (dbus_message_iter_get_arg_type(values.ptr) == DBUS_TYPE_STRING.toInt()) {
                                val uuid = alloc<CPointerVar<ByteVar>>()
                                dbus_message_iter_get_basic(values.ptr, uuid.ptr)
                                uuid.value?.toKString()?.let(parsed::add)
                                dbus_message_iter_next(values.ptr)
                            }
                            uuids = parsed
                        }
                    }
                }
            }
            dbus_message_iter_next(entries.ptr)
        }
        return connected to uuids
    }
}
