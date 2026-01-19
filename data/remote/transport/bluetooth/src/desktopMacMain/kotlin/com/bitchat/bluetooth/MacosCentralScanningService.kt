package com.bitchat.bluetooth

import com.bitchat.bluetooth.protocol.logInfo
import com.bitchat.bluetooth.service.AppleScanningService
import com.bitchat.bluetooth.service.IosSharedCentralManager
import platform.CoreBluetooth.CBManagerState
import platform.CoreBluetooth.CBPeripheral
import platform.Foundation.NSNumber

class MacosCentralScanningService(
    sharedCentralManager: IosSharedCentralManager
) : AppleScanningService, IosSharedCentralManager.ScanDelegate {
    private var onDeviceDiscoveredCallback: ((CBPeripheral, String?, Int) -> Unit)? = null

    init {
        sharedCentralManager.registerScanDelegate(this)
    }

    override fun setOnDeviceDiscoveredCallback(callback: (CBPeripheral, String?, Int) -> Unit) {
        this.onDeviceDiscoveredCallback = callback
    }

    override suspend fun startScan(lowLatency: Boolean) {
        logInfo("SCAN", "macOS BLE scanning - implementation pending")
        // TODO: Implement full macOS scanning when ready
    }

    override suspend fun stopScan() {
        // No-op for now
    }

    override fun onDeviceDiscovered(peripheral: CBPeripheral, advertisementData: Map<Any?, *>, rssi: NSNumber) {
        // No-op for now - will be implemented when full macOS Bluetooth support is ready
    }

    override fun onStateChange(state: CBManagerState) {
        // No-op for now
    }
}
