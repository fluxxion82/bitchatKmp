package com.bitchat.bluetooth.service

import com.bitchat.bluetooth.protocol.logDebug
import com.bitchat.bluetooth.protocol.logError
import com.bitchat.bluetooth.protocol.logInfo
import gattlib.*
import kotlinx.cinterop.*

/**
 * BlueZ BLE scanning service using GattLib.
 *
 * Implements Central role scanning to discover nearby bitchat devices
 * advertising the bitchat service UUID.
 */
@OptIn(ExperimentalForeignApi::class)
class BlueZScanningService(
    private val manager: BlueZManager
) : CentralScanningService, BlueZManager.ScanDelegate {

    companion object {
        private const val TAG = "BLUEZ_SCAN"
        private const val REDISCOVERY_LOG_INTERVAL_MS = 30_000L
    }

    // Device discovery callback
    private var onDeviceDiscoveredCallback: ((address: String, name: String?) -> Unit)? = null

    // Deduplication tracking
    private val discoveredDevices = mutableSetOf<String>()
    private val deviceDiscoveryTime = mutableMapOf<String, Long>()

    private var isScanning = false

    init {
        manager.registerScanDelegate(this)
    }

    /**
     * Set callback for when a device is discovered.
     */
    fun setOnDeviceDiscoveredCallback(callback: (address: String, name: String?) -> Unit) {
        this.onDeviceDiscoveredCallback = callback
    }

    override suspend fun startScan(lowLatency: Boolean) {
        logInfo(TAG, "Starting BLE scan...")

        val adapter = manager.getAdapter()
        if (adapter == null) {
            // Try to open adapter if not already open
            if (!manager.openAdapter()) {
                logError(TAG, "Failed to open adapter for scanning")
                return
            }
        }

        val adapterHandle = manager.getAdapter() ?: run {
            logError(TAG, "Adapter still null after open attempt")
            return
        }

        // Start GLib main loop BEFORE scanning - REQUIRED for scan callbacks to fire
        // GattLib uses D-Bus signals which only dispatch when GLib main loop is running
        manager.startMainLoop()

        isScanning = true

        // Scan for devices with UUID filter for bitchat service
        memScoped {
            // Store reference to this service for callback
            val userData = StableRef.create(this@BlueZScanningService).asCPointer()

            // Create UUID filter for bitchat service
            // UUID list must be NULL-terminated array of pointers
            val serviceUuid = alloc<uuid_t>()
            gattlib_string_to_uuid(
                BlueZManager.SERVICE_UUID,
                BlueZManager.SERVICE_UUID.length.toULong(),
                serviceUuid.ptr
            )

            // Create NULL-terminated array: [&uuid, NULL]
            val uuidList = allocArray<CPointerVar<uuid_t>>(2)
            uuidList[0] = serviceUuid.ptr
            uuidList[1] = null

            logInfo(TAG, "Enabling scan with UUID filter: ${BlueZManager.SERVICE_UUID}")
            manager.notifyScanStarted()

            // Use non-blocking scan with UUID filter
            val result = gattlib_adapter_scan_enable_with_filter_non_blocking(
                adapterHandle,
                uuidList,
                -127, // RSSI threshold (-127 = no threshold)
                GATTLIB_DISCOVER_FILTER_USE_UUID.toUInt(), // Enable UUID filtering
                staticCFunction(::scanCallback),
                0u, // timeout=0 for indefinite scan
                userData
            )

            if (result != GATTLIB_SUCCESS) {
                logError(TAG, "Failed to start scan with UUID filter, error code: $result")
                // Fallback: try without UUID filter (for debugging)
                logInfo(TAG, "Falling back to scan without UUID filter")
                val fallbackResult = gattlib_adapter_scan_enable_with_filter_non_blocking(
                    adapterHandle,
                    null,
                    -127,
                    0u,
                    staticCFunction(::scanCallback),
                    0u,
                    userData
                )
                if (fallbackResult != GATTLIB_SUCCESS) {
                    logError(TAG, "Fallback scan also failed, error code: $fallbackResult")
                    isScanning = false
                    manager.notifyScanStopped()
                } else {
                    logInfo(TAG, "Fallback scan started (no UUID filter)")
                }
            } else {
                logInfo(TAG, "Scan started with UUID filter")
            }
        }
    }

    override suspend fun stopScan() {
        if (!isScanning) {
            logDebug(TAG, "Scan not active, nothing to stop")
            return
        }

        logInfo(TAG, "Stopping BLE scan...")

        manager.getAdapter()?.let { adapter ->
            val result = gattlib_adapter_scan_disable(adapter)
            if (result != GATTLIB_SUCCESS) {
                logError(TAG, "Error stopping scan: $result")
            }
        }

        isScanning = false
        manager.notifyScanStopped()
        manager.stopMainLoop()
        logInfo(TAG, "Scan stopped")
    }

    // BlueZManager.ScanDelegate implementation
    override fun onDeviceDiscovered(address: String, name: String?) {
        val now = currentTimeMillis()
        val lastLogTime = deviceDiscoveryTime[address]
        val isNewDevice = !discoveredDevices.contains(address)
        val shouldLogRediscovery = lastLogTime != null && (now - lastLogTime) > REDISCOVERY_LOG_INTERVAL_MS

        if (isNewDevice) {
            logInfo(TAG, "New device: ${name ?: "Unknown"} ($address)")
            discoveredDevices.add(address)
            deviceDiscoveryTime[address] = now
        } else if (shouldLogRediscovery) {
            logDebug(TAG, "Rediscovered: ${name ?: "Unknown"} ($address)")
            deviceDiscoveryTime[address] = now
        }

        onDeviceDiscoveredCallback?.invoke(address, name)
    }

    override fun onScanStarted() {
        logDebug(TAG, "Scan delegate: started")
    }

    override fun onScanStopped() {
        logDebug(TAG, "Scan delegate: stopped")
    }

    /**
     * Clear discovered device tracking (useful when restarting scan).
     */
    fun clearDiscoveredDevices() {
        discoveredDevices.clear()
        deviceDiscoveryTime.clear()
    }

    /**
     * Get list of discovered device addresses.
     */
    fun getDiscoveredDevices(): Set<String> = discoveredDevices.toSet()
}

/**
 * Static callback function for GattLib scan results.
 * This is called from C code, so it must be a static function.
 */
@OptIn(ExperimentalForeignApi::class)
private fun scanCallback(
    adapter: CPointer<gattlib_adapter_t>?,
    addr: CPointer<ByteVar>?,
    name: CPointer<ByteVar>?,
    userData: COpaquePointer?
) {
    println("[SCAN_CB] Callback invoked! addr=${addr != null}, name=${name != null}, userData=${userData != null}")
    platform.posix.fflush(platform.posix.stdout)

    if (userData == null || addr == null) {
        println("[SCAN_CB] Skipping - null data")
        platform.posix.fflush(platform.posix.stdout)
        return
    }

    val service = userData.asStableRef<BlueZScanningService>().get()
    val address = addr.toKString()
    val deviceName = name?.toKString()

    println("[SCAN_CB] Device: $address, name: $deviceName")
    platform.posix.fflush(platform.posix.stdout)

    // Notify through the manager (which will call back to this service's delegate method)
    service.onDeviceDiscovered(address, deviceName)
}

/**
 * Get current time in milliseconds.
 * Uses platform.posix for Linux.
 */
@OptIn(ExperimentalForeignApi::class)
private fun currentTimeMillis(): Long {
    return memScoped {
        val tv = alloc<platform.posix.timeval>()
        platform.posix.gettimeofday(tv.ptr, null)
        tv.tv_sec * 1000L + tv.tv_usec / 1000L
    }
}
