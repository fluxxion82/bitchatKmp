package com.bitchat.bluetooth.service

interface GattServerService {
    suspend fun startAdvertising()
    suspend fun stopAdvertising()

    suspend fun onCharacteristicWriteRequest(data: ByteArray, deviceAddress: String)

    suspend fun notifyCharacteristic(deviceAddress: String, data: ByteArray): Boolean

    fun setDelegate(delegate: GattServerDelegate)
}

interface GattServerDelegate {
    fun onDataReceived(data: ByteArray, deviceAddress: String)
    fun onClientConnected(deviceAddress: String)
    fun onClientDisconnected(deviceAddress: String)
}
