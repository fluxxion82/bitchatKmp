package com.bitchat.bluetooth.service

class DesktopCentralScanningService : CentralScanningService {
    private var onDeviceDiscovered: ((String, String?, Int) -> Unit)? = null

    fun setOnDeviceDiscoveredCallback(callback: (String, String?, Int) -> Unit) {
        onDeviceDiscovered = callback
    }

    override suspend fun startScan(lowLatency: Boolean) {
        println("DesktopMacCentralScanningService.startScan: stub (lowLatency=$lowLatency)")
    }

    override suspend fun stopScan() {
        println("DesktopMacCentralScanningService.stopScan: stub")
    }

    private fun emit(deviceId: String, name: String?, rssi: Int) {
        onDeviceDiscovered?.invoke(deviceId, name, rssi)
    }
}
