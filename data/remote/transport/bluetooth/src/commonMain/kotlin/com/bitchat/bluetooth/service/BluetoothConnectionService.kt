package com.bitchat.bluetooth.service

interface BluetoothConnectionService {
    suspend fun connectToDevice(deviceAddress: String)
    suspend fun confirmDevice()
    suspend fun isDeviceConnecting(deviceAddress: String): Boolean
    suspend fun disconnectDeviceByAddress(deviceAddress: String)
    suspend fun clearConnections()
    suspend fun broadcastPacket(packetData: ByteArray)

    fun hasRequiredPermissions(): Boolean

    fun setConnectionEstablishedCallback(callback: ConnectionEstablishedCallback)
    fun setConnectionReadyCallback(callback: ConnectionReadyCallback)
    fun setOnPacketReceivedCallback(callback: OnPacketReceivedCallback)
}

interface ConnectionReadyCallback {
    fun onConnectionReady(deviceAddress: String)
}

interface OnPacketReceivedCallback {
    fun onPacketReceived(data: ByteArray, deviceAddress: String)
}
