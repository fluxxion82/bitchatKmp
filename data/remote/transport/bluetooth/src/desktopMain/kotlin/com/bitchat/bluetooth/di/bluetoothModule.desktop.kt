package com.bitchat.bluetooth.di

import com.bitchat.bluetooth.bridge.NativeBleBridge
import com.bitchat.bluetooth.service.AdvertisingService
import com.bitchat.bluetooth.service.BluetoothConnectionService
import com.bitchat.bluetooth.service.CentralScanningService
import com.bitchat.bluetooth.service.DesktopAdvertisingService
import com.bitchat.bluetooth.service.DesktopCentralScanningService
import com.bitchat.bluetooth.service.DesktopConnectionEventBus
import com.bitchat.bluetooth.service.DesktopConnectionService
import com.bitchat.bluetooth.service.DesktopGattClientService
import com.bitchat.bluetooth.service.DesktopGattServerService
import com.bitchat.bluetooth.service.GattClientService
import com.bitchat.bluetooth.service.GattServerService
import com.bitchat.bluetooth.service.NativeBleAdvertisingService
import com.bitchat.bluetooth.service.NativeBleConnectionService
import com.bitchat.bluetooth.service.NativeBleGattClientService
import com.bitchat.bluetooth.service.NativeBleGattServerService
import com.bitchat.bluetooth.service.NativeBleScanningService
import com.bitchat.domain.connectivity.eventbus.ConnectionEventBus
import kotlinx.coroutines.runBlocking
import org.koin.dsl.module

actual val platformBleModule = module {
    val useNative = System.getProperty("ble.native")?.lowercase() == "macos"
    val nativeLoaded = if (useNative) NativeBleBridge.init() else false
    single<AdvertisingService> {
        val fallback = DesktopAdvertisingService()
        if (nativeLoaded) NativeBleAdvertisingService(fallback, true) else fallback
    }
    single<CentralScanningService> {
        val fallback = DesktopCentralScanningService()
        if (nativeLoaded) {
            NativeBleScanningService(fallback, true).apply {
                setOnDeviceDiscoveredCallback { id, name, rssi ->
                    val connection = get<BluetoothConnectionService>()
                    runBlocking {
                        connection.connectToDevice(id)
                    }
                }
            }
        } else {
            fallback
        }
    }
    single<GattServerService> {
        val fallback = DesktopGattServerService()
        if (nativeLoaded) NativeBleGattServerService(fallback, true) else fallback
    }
    single<GattClientService> {
        val fallback = DesktopGattClientService()
        if (nativeLoaded) NativeBleGattClientService(fallback, true) else fallback
    }
    single<BluetoothConnectionService> {
        val fallback = DesktopConnectionService()
        if (nativeLoaded) NativeBleConnectionService(fallback) else fallback
    }
    single<ConnectionEventBus> { DesktopConnectionEventBus() }
}
