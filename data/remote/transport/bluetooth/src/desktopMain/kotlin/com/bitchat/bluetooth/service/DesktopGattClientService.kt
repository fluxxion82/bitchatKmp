package com.bitchat.bluetooth.service

class DesktopGattClientService : GattClientService {
    override suspend fun writeCharacteristic(deviceAddress: String, data: ByteArray): Boolean {
        println("DesktopGattClientService.writeCharacteristic: Desktop BLE not implemented")
        return false
    }

    override suspend fun disconnect(deviceAddress: String) {
        println("DesktopGattClientService.disconnect: Desktop BLE not implemented")
    }

    override suspend fun disconnectAll() {
        println("DesktopGattClientService.disconnectAll: Desktop BLE not implemented")
    }

    override fun setDelegate(delegate: GattClientDelegate) {
        // No-op
    }
}
