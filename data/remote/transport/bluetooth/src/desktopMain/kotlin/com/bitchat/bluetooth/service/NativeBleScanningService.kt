package com.bitchat.bluetooth.service

import com.bitchat.bluetooth.bridge.NativeBleBridge

class NativeBleScanningService(
    private val fallback: CentralScanningService,
    private val nativeAvailable: Boolean,
) : CentralScanningService {
    private var discoveryRegistered = false
    private var onDeviceDiscovered: ((String, String?, Int) -> Unit)? = null
    private val seenDevices = mutableSetOf<String>()

    init {
        if (nativeAvailable) {
            registerDiscovery()
        }
    }

    fun setOnDeviceDiscoveredCallback(callback: (String, String?, Int) -> Unit) {
        onDeviceDiscovered = callback
    }

    override suspend fun startScan(lowLatency: Boolean) {
        if (nativeAvailable) {
            registerDiscovery()
            val ok = NativeBleBridge.startScan(lowLatency)
            println("NativeBleScanningService.startScan: native bridge ${if (ok) "ok" else "failed"} (lowLatency=$lowLatency)")
        } else {
            fallback.startScan(lowLatency)
        }
    }

    override suspend fun stopScan() {
        if (nativeAvailable) {
            val ok = NativeBleBridge.stopScan()
            println("NativeBleScanningService.stopScan: native bridge ${if (ok) "ok" else "failed"}")
            synchronized(seenDevices) { seenDevices.clear() }
        } else {
            fallback.stopScan()
        }
    }

    private fun registerDiscovery() {
        if (discoveryRegistered) return
        NativeBleBridge.registerDiscoveryCallback(object : NativeBleBridge.DiscoveryCallback {
            override fun invoke(deviceId: String?, name: String?, rssi: Int) {
                val id = deviceId ?: return
                synchronized(seenDevices) {
                    if (!seenDevices.add(id)) {
                        return
                    }
                }
                println("NativeBleScanningService.discovery: id=$id name=$name rssi=$rssi (forwarding to connection)")
                onDeviceDiscovered?.invoke(id, name, rssi)
            }
        })
        discoveryRegistered = true
        println("NativeBleScanningService: discovery callback registered (native)")
    }
}
