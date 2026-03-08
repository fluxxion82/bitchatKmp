package com.bitchat.bluetooth.di

import com.bitchat.bluetooth.service.AdvertisingService
import com.bitchat.bluetooth.service.BlueZAdvertisingService
import com.bitchat.bluetooth.service.BlueZConnectionService
import com.bitchat.bluetooth.service.BlueZGattClientService
import com.bitchat.bluetooth.service.BlueZGattServerService
import com.bitchat.bluetooth.service.BlueZManager
import com.bitchat.bluetooth.service.BlueZScanningService
import com.bitchat.bluetooth.service.BluetoothConnectionService
import com.bitchat.bluetooth.service.CentralScanningService
import com.bitchat.bluetooth.service.GattClientService
import com.bitchat.bluetooth.service.GattServerService
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * Linux BLE module providing BlueZ-based implementations.
 *
 * Uses GattLib for Central role (scanning, GATT client) and
 * D-Bus for Peripheral role (advertising, GATT server).
 *
 * Requires BlueZ to be installed and running on the target device.
 */
actual val platformBleModule: Module = module {
    // Shared BlueZ adapter manager
    single { BlueZManager() }

    // Central role services (GattLib)
    single { BlueZScanningService(get()) } bind CentralScanningService::class
    single { BlueZGattClientService(get()) } bind GattClientService::class

    // Peripheral role services (D-Bus)
    single { BlueZGattServerService(get()) } bind GattServerService::class
    single { BlueZAdvertisingService(get()) } bind AdvertisingService::class

    // Connection orchestrator
    single {
        BlueZConnectionService(
            coroutineScopeFacade = get(),
            manager = get(),
            scanningService = get(),
            gattClient = get(),
            gattServer = get(),
            advertisingService = get()
        )
    } bind BluetoothConnectionService::class
}
