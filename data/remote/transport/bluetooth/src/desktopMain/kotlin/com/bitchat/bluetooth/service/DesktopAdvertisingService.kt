package com.bitchat.bluetooth.service

class DesktopAdvertisingService : AdvertisingService {
    override suspend fun startAdvertising(serviceUuid: String, deviceName: String) {
        println("DesktopAdvertisingService.startAdvertising: Desktop BLE not implemented")
    }

    override suspend fun stopAdvertising() {
        println("DesktopAdvertisingService.stopAdvertising: Desktop BLE not implemented")
    }

    override fun isAdvertising(): Boolean = false
}
