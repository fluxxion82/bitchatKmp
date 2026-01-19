package com.bitchat.bluetooth.service

import com.bitchat.local.bridge.NativeLocationBridge

/**
 * Desktop stub connection service; always reports unsupported and no-ops.
 */
class DesktopConnectionService : BluetoothConnectionService {
    private var establishedCallback: ConnectionEstablishedCallback? = null
    private var readyCallback: ConnectionReadyCallback? = null

    override suspend fun connectToDevice(deviceAddress: String) {
        println("DesktopConnectionService.connectToDevice: Desktop BLE not implemented ($deviceAddress)")
    }

    override suspend fun confirmDevice() {
        println("DesktopConnectionService.confirmDevice: Desktop BLE not implemented")
    }

    override suspend fun isDeviceConnecting(deviceAddress: String): Boolean {
        println("DesktopConnectionService.isDeviceConnecting: Desktop BLE not implemented ($deviceAddress)")
        return false
    }

    override suspend fun disconnectDeviceByAddress(deviceAddress: String) {
        println("DesktopConnectionService.disconnectDeviceByAddress: Desktop BLE not implemented ($deviceAddress)")
    }

    override suspend fun clearConnections() {
        println("DesktopConnectionService.clearConnections: Desktop BLE not implemented")
    }

    override suspend fun broadcastPacket(packetData: ByteArray) {
        println("DesktopConnectionService.broadcastPacket: Desktop BLE not implemented (${packetData.size} bytes)")
    }

    override fun hasRequiredPermissions(): Boolean {
        val useNativeLocation = System.getProperty("location.native")?.lowercase() == "macos"
        return if (useNativeLocation && NativeLocationBridge.isAvailable()) {
            NativeLocationBridge.hasPermission()
        } else {
            true // IP-based fallback doesn't require permission
        }
    }

    override fun setConnectionEstablishedCallback(callback: ConnectionEstablishedCallback) {
        establishedCallback = callback
    }

    override fun setConnectionReadyCallback(callback: ConnectionReadyCallback) {
        readyCallback = callback
    }
}
