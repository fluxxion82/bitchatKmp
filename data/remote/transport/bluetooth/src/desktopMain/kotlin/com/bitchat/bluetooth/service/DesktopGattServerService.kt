package com.bitchat.bluetooth.service

class DesktopGattServerService : GattServerService {
    override suspend fun startAdvertising() {
        println("DesktopGattServerService.startAdvertising: Desktop BLE not implemented")
    }

    override suspend fun stopAdvertising() {
        println("DesktopGattServerService.stopAdvertising: Desktop BLE not implemented")
    }

    override suspend fun onCharacteristicWriteRequest(data: ByteArray, deviceAddress: String) {
        println("DesktopGattServerService.onCharacteristicWriteRequest: Desktop BLE not implemented")
    }

    override suspend fun notifyCharacteristic(deviceAddress: String, data: ByteArray): Boolean {
        println("DesktopGattServerService.notifyCharacteristic: Desktop BLE not implemented")
        return false
    }

    override fun setDelegate(delegate: GattServerDelegate) {
        // No-op
    }
}
