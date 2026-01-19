package com.bitchat.bluetooth.service

class DesktopGattClientService : GattClientService {
    private var delegate: GattClientDelegate? = null

    override suspend fun writeCharacteristic(deviceAddress: String, data: ByteArray): Boolean {
        println("DesktopMacGattClientService.writeCharacteristic: stub ($deviceAddress, ${data.size} bytes)")
        return false
    }

    override suspend fun disconnect(deviceAddress: String) {
        println("DesktopMacGattClientService.disconnect: stub ($deviceAddress)")
    }

    override suspend fun disconnectAll() {
        println("DesktopMacGattClientService.disconnectAll: stub")
    }

    override fun setDelegate(delegate: GattClientDelegate) {
        this.delegate = delegate
    }
}
