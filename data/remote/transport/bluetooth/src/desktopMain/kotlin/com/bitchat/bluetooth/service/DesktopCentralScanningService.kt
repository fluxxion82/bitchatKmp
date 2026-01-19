package com.bitchat.bluetooth.service

/**
 * Desktop stub for Central scanning; logs calls and no-ops.
 */
class DesktopCentralScanningService : CentralScanningService {
    override suspend fun startScan(lowLatency: Boolean) {
        println("DesktopCentralScanningService.startScan: Desktop BLE not implemented (lowLatency=$lowLatency)")
    }

    override suspend fun stopScan() {
        println("DesktopCentralScanningService.stopScan: Desktop BLE not implemented")
    }
}
