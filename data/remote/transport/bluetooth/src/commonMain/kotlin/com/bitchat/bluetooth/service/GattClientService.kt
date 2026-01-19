package com.bitchat.bluetooth.service

interface GattClientService {
    suspend fun writeCharacteristic(deviceAddress: String, data: ByteArray): Boolean
    suspend fun disconnect(deviceAddress: String)
    suspend fun disconnectAll()
    fun setDelegate(delegate: GattClientDelegate)
}

interface GattClientDelegate {
    fun onCharacteristicRead(deviceAddress: String, data: ByteArray)
    fun onWriteSuccess(deviceAddress: String)
    fun onWriteFailure(deviceAddress: String, error: String)
}
