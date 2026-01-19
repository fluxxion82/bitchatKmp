package com.bitchat.bluetooth.service

import platform.CoreBluetooth.CBPeripheral

/**
 * Apple-specific scanning service interface that extends CentralScanningService
 * with device discovery callback support.
 *
 * Both iOS and macOS scanning service implementations implement this interface.
 */
interface AppleScanningService : CentralScanningService {
    fun setOnDeviceDiscoveredCallback(callback: (CBPeripheral, String?, Int) -> Unit)
}
