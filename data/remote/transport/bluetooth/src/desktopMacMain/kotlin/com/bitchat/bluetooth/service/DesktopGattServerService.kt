package com.bitchat.bluetooth.service

class DesktopGattServerService : GattServerService {
    private var delegate: GattServerDelegate? = null

    override suspend fun startAdvertising() {
        println("DesktopMacGattServerService.startAdvertising: stub")
    }

    override suspend fun stopAdvertising() {
        println("DesktopMacGattServerService.stopAdvertising: stub")
    }

    override suspend fun onCharacteristicWriteRequest(data: ByteArray, deviceAddress: String) {
        println("DesktopMacGattServerService.onCharacteristicWriteRequest: stub ($deviceAddress, ${data.size} bytes)")
    }

    override suspend fun notifyCharacteristic(deviceAddress: String, data: ByteArray): Boolean {
        println("DesktopMacGattServerService.notifyCharacteristic: stub ($deviceAddress, ${data.size} bytes)")
        return false
    }

    override fun setDelegate(delegate: GattServerDelegate) {
        this.delegate = delegate
    }
}
