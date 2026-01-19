package com.bitchat.bluetooth.service

import com.bitchat.bluetooth.bridge.NativeBleBridge

class NativeBleAdvertisingService(
    private val fallback: AdvertisingService,
    private val nativeAvailable: Boolean,
) : AdvertisingService {
    override suspend fun startAdvertising(serviceUuid: String, deviceName: String) {
        if (nativeAvailable) {
            val ok = NativeBleBridge.startAdvertising(serviceUuid, deviceName)
            println("NativeBleAdvertisingService.startAdvertising: native bridge ${if (ok) "ok" else "failed"} (uuid=$serviceUuid, name=$deviceName)")
        } else {
            fallback.startAdvertising(serviceUuid, deviceName)
        }
    }

    override suspend fun stopAdvertising() {
        if (nativeAvailable) {
            val ok = NativeBleBridge.stopAdvertising()
            println("NativeBleAdvertisingService.stopAdvertising: native bridge ${if (ok) "ok" else "failed"}")
        } else {
            fallback.stopAdvertising()
        }
    }

    override fun isAdvertising(): Boolean =
        if (nativeAvailable) {
            false
        } else {
            fallback.isAdvertising()
        }
}
