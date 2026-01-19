package com.bitchat.bluetooth.di

import com.bitchat.bluetooth.service.AdvertisingService
import com.bitchat.bluetooth.service.AppleScanningService
import com.bitchat.bluetooth.service.BluetoothConnectionService
import com.bitchat.bluetooth.service.BluetoothMeshService
import com.bitchat.bluetooth.service.CentralScanningService
import com.bitchat.bluetooth.service.GattClientService
import com.bitchat.bluetooth.service.GattServerService
import com.bitchat.bluetooth.service.IosAdvertisingService
// ConnectionEventBus binding lives in iosMain
import com.bitchat.bluetooth.service.IosConnectionService
import com.bitchat.bluetooth.service.IosGattClientService
import com.bitchat.bluetooth.service.IosGattServerService
import com.bitchat.bluetooth.service.IosSharedCentralManager
import org.koin.dsl.bind
import org.koin.dsl.module

actual val platformBleModule = module {
    single { IosSharedCentralManager() }
    single { IosGattServerService() }
    single { IosGattClientService(sharedCentralManager = get()) }
    single<GattServerService> { get<IosGattServerService>() }
    single<GattClientService> { get<IosGattClientService>() }

    single<AppleScanningService> {
        createPlatformScanningService(
            coroutineScopeFacade = get(),
            sharedCentralManager = get(),
        ).apply {
            setOnDeviceDiscoveredCallback { peripheral, deviceName, rssi ->
                val connectionService = get<IosConnectionService>()
                kotlinx.coroutines.runBlocking {
                    connectionService.onDeviceDiscovered(peripheral, deviceName, rssi)
                }
            }
        }
    }

    single<CentralScanningService> { get<AppleScanningService>() }

    single {
        val connectionService = IosConnectionService(
            coroutineScopeFacade = get(),
            gattServer = get<IosGattServerService>(),
            gattClient = get<IosGattClientService>(),
        ).apply {
            setOnPacketReceivedCallback { data, deviceAddress ->
                val meshService: BluetoothMeshService = get()
                meshService.onPacketReceived(data, deviceAddress)
            }
        }

        connectionService
    } bind BluetoothConnectionService::class

    single<AdvertisingService> { IosAdvertisingService() }
}
