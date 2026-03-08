package com.bitchat.bluetooth.service

import com.bitchat.bluetooth.protocol.logDebug
import com.bitchat.bluetooth.protocol.logError
import com.bitchat.bluetooth.protocol.logInfo
import gattlib.*
import kotlinx.cinterop.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.AtomicInt
import kotlin.native.concurrent.Worker

/**
 * Shared BlueZ adapter manager for Linux BLE operations.
 *
 * This class manages:
 * - GattLib adapter for Central role (scanning, connecting, GATT client)
 * - Shared state for all BLE services
 *
 * GattLib is client-only (Central role). For Peripheral role (advertising,
 * GATT server), the individual services use D-Bus directly.
 */
@OptIn(ExperimentalForeignApi::class)
class BlueZManager {
    companion object {
        private const val TAG = "BLUEZ_MGR"
        const val SERVICE_UUID = "F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C"
        const val CHARACTERISTIC_UUID = "A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5D"
    }

    /**
     * BLE adapter state
     */
    enum class AdapterState {
        UNINITIALIZED,
        OPENING,
        READY,
        SCANNING,
        ERROR,
        CLOSED
    }

    private val _adapterState = MutableStateFlow(AdapterState.UNINITIALIZED)
    val adapterState: StateFlow<AdapterState> = _adapterState.asStateFlow()

    // GattLib adapter handle (nullable, initialized on open)
    private var adapter: CPointer<gattlib_adapter_t>? = null

    // GLib main loop worker for dispatching callbacks
    private var mainLoopWorker: Worker? = null
    internal val mainLoopRunning = AtomicInt(0)

    // Registered delegates for scan and GATT events
    private var scanDelegate: ScanDelegate? = null
    private var gattDelegate: GattDelegate? = null

    /**
     * Delegate for scan events
     */
    interface ScanDelegate {
        fun onDeviceDiscovered(address: String, name: String?)
        fun onScanStarted()
        fun onScanStopped()
    }

    /**
     * Delegate for GATT client events
     */
    interface GattDelegate {
        fun onConnected(address: String, connection: CPointer<gattlib_connection_t>)
        fun onDisconnected(address: String, error: String?)
        fun onNotification(address: String, uuid: CValue<uuid_t>, data: ByteArray)
    }

    fun registerScanDelegate(delegate: ScanDelegate) {
        this.scanDelegate = delegate
    }

    fun registerGattDelegate(delegate: GattDelegate) {
        this.gattDelegate = delegate
    }

    /**
     * Open the default Bluetooth adapter.
     *
     * @return true if adapter opened successfully
     */
    fun openAdapter(): Boolean {
        if (adapter != null) {
            logDebug(TAG, "Adapter already open")
            return true
        }

        _adapterState.value = AdapterState.OPENING
        logInfo(TAG, "Opening BlueZ adapter...")

        return memScoped {
            val adapterPtr = alloc<CPointerVar<gattlib_adapter_t>>()

            // Open default adapter (pass null for adapter name)
            val result = gattlib_adapter_open(null, adapterPtr.ptr)

            if (result == GATTLIB_SUCCESS) {
                adapter = adapterPtr.value
                _adapterState.value = AdapterState.READY
                logInfo(TAG, "Adapter opened successfully")
                true
            } else {
                _adapterState.value = AdapterState.ERROR
                logError(TAG, "Failed to open adapter, error code: $result")
                false
            }
        }
    }

    /**
     * Close the Bluetooth adapter and release resources.
     */
    fun closeAdapter() {
        adapter?.let { adapterHandle ->
            logInfo(TAG, "Closing BlueZ adapter...")
            gattlib_adapter_close(adapterHandle)
            adapter = null
            _adapterState.value = AdapterState.CLOSED
            logInfo(TAG, "Adapter closed")
        }
    }

    /**
     * Get the GattLib adapter handle.
     * Returns null if adapter is not open.
     */
    fun getAdapter(): CPointer<gattlib_adapter_t>? = adapter

    /**
     * Check if adapter is ready for operations.
     */
    fun isReady(): Boolean = _adapterState.value == AdapterState.READY ||
            _adapterState.value == AdapterState.SCANNING

    /**
     * Convert a UUID string to GattLib uuid_t structure.
     */
    fun stringToUuid(uuidString: String): CValue<uuid_t> = memScoped {
        val uuid = alloc<uuid_t>()
        gattlib_string_to_uuid(uuidString, uuidString.length.toULong(), uuid.ptr)
        uuid.readValue()
    }

    /**
     * Convert a GattLib uuid_t to string.
     */
    fun uuidToString(uuid: uuid_t): String = memScoped {
        val buffer = allocArray<ByteVar>(37) // UUID string is 36 chars + null
        gattlib_uuid_to_string(uuid.ptr, buffer, 37u)
        buffer.toKString()
    }

    // Internal: Called by scanning service when scan discovers a device
    internal fun notifyDeviceDiscovered(address: String, name: String?) {
        scanDelegate?.onDeviceDiscovered(address, name)
    }

    // Internal: Called by scanning service when scan starts
    internal fun notifyScanStarted() {
        _adapterState.value = AdapterState.SCANNING
        scanDelegate?.onScanStarted()
    }

    // Internal: Called by scanning service when scan stops
    internal fun notifyScanStopped() {
        if (_adapterState.value == AdapterState.SCANNING) {
            _adapterState.value = AdapterState.READY
        }
        scanDelegate?.onScanStopped()
    }

    // Internal: Called by client service when connected
    internal fun notifyConnected(address: String, connection: CPointer<gattlib_connection_t>) {
        gattDelegate?.onConnected(address, connection)
    }

    // Internal: Called by client service when disconnected
    internal fun notifyDisconnected(address: String, error: String?) {
        gattDelegate?.onDisconnected(address, error)
    }

    // Internal: Called when notification received
    internal fun notifyNotification(address: String, uuid: CValue<uuid_t>, data: ByteArray) {
        gattDelegate?.onNotification(address, uuid, data)
    }

    /**
     * Start the GLib main loop in a background thread.
     * This is REQUIRED for GattLib callbacks to fire.
     * Call this after opening the adapter but before starting scan.
     *
     * IMPORTANT: gattlib_mainloop() runs the GLib main loop until the task function returns.
     * The task function runs in a separate GLib thread - it must block while we want the
     * main loop to keep running. When the task returns, gattlib calls g_main_loop_quit().
     */
    fun startMainLoop() {
        if (mainLoopRunning.compareAndSet(0, 1)) {
            logInfo(TAG, "Starting GLib main loop thread...")

            // Store reference to mainLoopRunning for the static callback
            mainLoopStopFlag = this

            mainLoopWorker = Worker.start(name = "GLibMainLoop")
            mainLoopWorker?.execute(kotlin.native.concurrent.TransferMode.SAFE, { Unit }) {
                logInfo(TAG, "GLib main loop thread started")
                platform.posix.fflush(platform.posix.stdout)

                // gattlib_mainloop runs the GLib main loop.
                // The task runs in a separate thread - it must BLOCK while we want
                // the main loop to keep running. When task returns, main loop quits.
                val result = gattlib_mainloop(
                    staticCFunction(::mainLoopTask),
                    null
                )

                logInfo(TAG, "GLib main loop exited with code: $result")
                platform.posix.fflush(platform.posix.stdout)
            }

            // Give the main loop time to start
            platform.posix.usleep(100_000u) // 100ms
            logInfo(TAG, "GLib main loop thread launched")
        } else {
            logDebug(TAG, "GLib main loop already running")
        }
    }

    /**
     * Stop the GLib main loop thread.
     */
    fun stopMainLoop() {
        if (mainLoopRunning.compareAndSet(1, 0)) {
            logInfo(TAG, "Stopping GLib main loop...")
            // Setting mainLoopRunning to 0 will cause mainLoopTask to return,
            // which triggers g_main_loop_quit() in gattlib
            mainLoopWorker = null
        }
    }
}

// Global reference for static callback to check stop flag
private var mainLoopStopFlag: BlueZManager? = null

/**
 * Task function for gattlib_mainloop.
 * This runs in a GLib thread and must block while we want the main loop running.
 * When this returns, gattlib calls g_main_loop_quit() and the main loop stops.
 */
@OptIn(ExperimentalForeignApi::class)
private fun mainLoopTask(arg: COpaquePointer?): COpaquePointer? {
    println("[BLUEZ_MGR] Main loop task started, blocking...")
    platform.posix.fflush(platform.posix.stdout)

    // Block while mainLoopRunning is 1
    // Check every 100ms so we can respond to stop requests
    while (mainLoopStopFlag?.mainLoopRunning?.value == 1) {
        platform.posix.usleep(100_000u) // 100ms
    }

    println("[BLUEZ_MGR] Main loop task exiting (stop requested)")
    platform.posix.fflush(platform.posix.stdout)
    return null
}
