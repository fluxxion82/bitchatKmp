package com.bitchat.bluetooth.service

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DesktopConnectionService(
    private val gattServer: DesktopGattServerService,
    private val gattClient: DesktopGattClientService,
) : BluetoothConnectionService {

    private var establishedCallback: ConnectionEstablishedCallback? = null
    private var readyCallback: ConnectionReadyCallback? = null
    private var onPacketReceived: ((ByteArray, String) -> Unit)? = null

    private val connected = mutableSetOf<String>()
    private val mutex = Mutex()

    override suspend fun connectToDevice(deviceAddress: String) {
        println("DesktopMacConnectionService.connectToDevice: stub ($deviceAddress)")
        establishedCallback?.onDeviceConnected(deviceAddress)
        readyCallback?.onConnectionReady(deviceAddress)
    }

    override suspend fun confirmDevice() {
        println("DesktopMacConnectionService.confirmDevice: stub")
    }

    override suspend fun isDeviceConnecting(deviceAddress: String): Boolean = false

    override suspend fun disconnectDeviceByAddress(deviceAddress: String) {
        println("DesktopMacConnectionService.disconnectDeviceByAddress: stub ($deviceAddress)")
        mutex.withLock { connected.remove(deviceAddress) }
    }

    override suspend fun clearConnections() {
        println("DesktopMacConnectionService.clearConnections: stub")
        mutex.withLock { connected.clear() }
    }

    override suspend fun broadcastPacket(packetData: ByteArray) {
        println("DesktopMacConnectionService.broadcastPacket: stub (${packetData.size} bytes)")
    }

    override fun hasRequiredPermissions(): Boolean = true

    override fun setConnectionEstablishedCallback(callback: ConnectionEstablishedCallback) {
        establishedCallback = callback
    }

    override fun setConnectionReadyCallback(callback: ConnectionReadyCallback) {
        readyCallback = callback
    }

    fun setOnPacketReceivedCallback(callback: (ByteArray, String) -> Unit) {
        onPacketReceived = callback
    }
}
