package com.bitchat.bluetooth.di

import com.bitchat.bluetooth.service.AdvertisingService
import com.bitchat.bluetooth.service.BluetoothConnectionService
import com.bitchat.bluetooth.service.CentralScanningService
import com.bitchat.bluetooth.service.DesktopAdvertisingService
import com.bitchat.bluetooth.service.DesktopCentralScanningService
import com.bitchat.bluetooth.service.DesktopConnectionService
import com.bitchat.bluetooth.service.DesktopGattClientService
import com.bitchat.bluetooth.service.DesktopGattServerService
import com.bitchat.bluetooth.service.GattClientService
import com.bitchat.bluetooth.service.GattServerService
import org.koin.dsl.module

actual val platformBleModule = module {
    single<GattServerService> { DesktopGattServerService() }
    single<GattClientService> { DesktopGattClientService() }
    single<CentralScanningService> { DesktopCentralScanningService() }
    single<AdvertisingService> { DesktopAdvertisingService() }
    single<BluetoothConnectionService> { DesktopConnectionService(get(), get()) }
}
