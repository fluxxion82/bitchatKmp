package com.bitchat.bluetooth.service

class DesktopAdvertisingService : AdvertisingService {
    private var advertising = false

    override suspend fun startAdvertising(serviceUuid: String, deviceName: String) {
        // TODO: Wire CBPeripheralManager advertising
        advertising = true
        println("DesktopMacAdvertisingService.startAdvertising: stub (uuid=$serviceUuid, name=$deviceName)")
    }

    override suspend fun stopAdvertising() {
        advertising = false
        println("DesktopMacAdvertisingService.stopAdvertising: stub")
    }

    override fun isAdvertising(): Boolean = advertising
}
