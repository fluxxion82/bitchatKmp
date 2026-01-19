package com.bitchat.bluetooth

import com.bitchat.bluetooth.protocol.logDebug
import com.bitchat.bluetooth.protocol.logError
import com.bitchat.bluetooth.protocol.logInfo
import com.bitchat.bluetooth.service.AppleScanningService
import com.bitchat.bluetooth.service.IosSharedCentralManager
import com.bitchat.domain.base.CoroutineScopeFacade
import kotlinx.coroutines.launch
import platform.CoreBluetooth.CBCentralManagerScanOptionAllowDuplicatesKey
import platform.CoreBluetooth.CBManagerState
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSDate
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillEnterForegroundNotification

class IosCentralScanningService(
    private val coroutineScopeFacade: CoroutineScopeFacade,
    private val sharedCentralManager: IosSharedCentralManager
) : AppleScanningService, IosSharedCentralManager.ScanDelegate {

    private var isScanning = false
    private var isForeground = true
    private var shouldResumeScan = false

    private var onDeviceDiscoveredCallback: ((CBPeripheral, String?, Int) -> Unit)? = null
    private val notificationTokens = mutableListOf<Any>()

    // Deduplication tracking for discovered devices
    private val discoveredDevices = mutableSetOf<String>()
    private val deviceDiscoveryTime = mutableMapOf<String, Long>()

    init {
        sharedCentralManager.registerScanDelegate(this)
        observeLifecycle()
    }

    override fun setOnDeviceDiscoveredCallback(callback: (CBPeripheral, String?, Int) -> Unit) {
        this.onDeviceDiscoveredCallback = callback
    }

    override suspend fun startScan(lowLatency: Boolean) {
        logInfo("SCAN", "Starting BLE scan (foreground: $isForeground)")

        isScanning = true
        shouldResumeScan = true
        if (sharedCentralManager.getState() == CBManagerStatePoweredOn) {
            val serviceUuid = CBUUID.UUIDWithString(SERVICE_UUID)
            val options: Map<Any?, Any?> =
                mapOf(CBCentralManagerScanOptionAllowDuplicatesKey to isForeground)
            sharedCentralManager.startScan(listOf(serviceUuid), options)
            logDebug("SCAN", "Scan initiated for UUID: $SERVICE_UUID")
        } else {
            logError("SCAN", "Cannot start scan - Bluetooth not powered on (state: ${sharedCentralManager.getState()})")
        }
    }

    override suspend fun stopScan() {
        isScanning = false
        shouldResumeScan = false
        sharedCentralManager.stopScan()
    }

    private fun restartScanIfNeeded() {
        if (!isScanning || sharedCentralManager.getState() != CBManagerStatePoweredOn) {
            return
        }
        sharedCentralManager.stopScan()
        val serviceUuid = CBUUID.UUIDWithString(SERVICE_UUID)
        val options: Map<Any?, Any?> =
            mapOf(CBCentralManagerScanOptionAllowDuplicatesKey to isForeground)
        sharedCentralManager.startScan(listOf(serviceUuid), options)
    }

    private fun observeLifecycle() {
        val center = NSNotificationCenter.defaultCenter
        val foregroundToken = center.addObserverForName(
            name = UIApplicationWillEnterForegroundNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue
        ) {
            isForeground = true
            restartScanIfNeeded()
        }
        val backgroundToken = center.addObserverForName(
            name = UIApplicationDidEnterBackgroundNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue
        ) {
            isForeground = false
            restartScanIfNeeded()
        }

        notificationTokens.add(foregroundToken)
        notificationTokens.add(backgroundToken)
    }

    override fun onDeviceDiscovered(peripheral: CBPeripheral, advertisementData: Map<Any?, *>, rssi: NSNumber) {
        val rssiValue = rssi.intValue
        val deviceName = peripheral.name
        val deviceId = peripheral.identifier.UUIDString

        val now = (NSDate().timeIntervalSince1970 * 1000).toLong()
        val lastLogTime = deviceDiscoveryTime[deviceId]
        val isNewDevice = !discoveredDevices.contains(deviceId)
        val shouldLogRediscovery = lastLogTime != null && (now - lastLogTime) > REDISCOVERY_LOG_INTERVAL_MS

        if (isNewDevice) {
            logInfo("SCAN", "New device: ${deviceName ?: "Unknown"} (${deviceId.take(8)}...) RSSI: $rssiValue")
            discoveredDevices.add(deviceId)
            deviceDiscoveryTime[deviceId] = now
        } else if (shouldLogRediscovery) {
            logDebug("SCAN", "Rediscovered: ${deviceName ?: "Unknown"} (${deviceId.take(8)}...) RSSI: $rssiValue")
            deviceDiscoveryTime[deviceId] = now
        }

        coroutineScopeFacade.applicationScope.launch {
            onDeviceDiscoveredCallback?.invoke(peripheral, deviceName, rssiValue)
        }
    }

    override fun onStateChange(state: CBManagerState) {
        logDebug("SCAN", "BT state changed: $state (shouldResumeScan: $shouldResumeScan)")
        if (state == CBManagerStatePoweredOn && shouldResumeScan) {
            logInfo("SCAN", "Restarting scan (BT powered on)")
            restartScanIfNeeded()
        }
    }

    companion object {
        private const val CENTRAL_RESTORE_ID = "com.bitchat.bluetooth.scan"
        private const val SERVICE_UUID = "F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C"
        private const val REDISCOVERY_LOG_INTERVAL_MS = 30_000L  // Log same device max once per 30s
    }
}
