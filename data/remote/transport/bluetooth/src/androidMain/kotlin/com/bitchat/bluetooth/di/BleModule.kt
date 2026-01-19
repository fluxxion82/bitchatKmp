package com.bitchat.bluetooth.di

import com.bitchat.bluetooth.AndroidCentralScanningService
import com.bitchat.bluetooth.service.AdvertisingService
import com.bitchat.bluetooth.service.AndroidAdvertisingService
import com.bitchat.bluetooth.service.AndroidConnectionEventBus
import com.bitchat.bluetooth.service.AndroidConnectionService
import com.bitchat.bluetooth.service.AndroidGattClientService
import com.bitchat.bluetooth.service.AndroidGattServerService
import com.bitchat.bluetooth.service.BluetoothConnectionService
import com.bitchat.bluetooth.service.BluetoothMeshService
import com.bitchat.bluetooth.service.CentralScanningService
import com.bitchat.bluetooth.service.GattClientConnectionDelegate
import com.bitchat.bluetooth.service.GattClientService
import com.bitchat.bluetooth.service.GattServerService
import com.bitchat.domain.connectivity.eventbus.ConnectionEventBus
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.bind
import org.koin.dsl.binds
import org.koin.dsl.module

actual val platformBleModule = module {
    single {
        AndroidGattServerService(
            context = androidContext(),
            coroutineScopeFacade = get(),
        )
    }

    single {
        AndroidGattClientService(
            context = androidContext(),
            coroutineScopeFacade = get(),
        )
    }

    single<GattServerService> { get<AndroidGattServerService>() }
    single<GattClientService> { get<AndroidGattClientService>() }

    single {
        val connectionService = AndroidConnectionService(
            context = androidContext(),
            coroutineScopeFacade = get(),
            coroutineContextFacade = get(),
            gattServer = get<AndroidGattServerService>(),
            gattClient = get<AndroidGattClientService>(),
        ).apply {
            setOnPacketReceivedCallback { data, deviceAddress ->
                val meshService: BluetoothMeshService = get()
                meshService.onPacketReceived(data, deviceAddress)
            }
        }

        val gattClient = get<AndroidGattClientService>()
        gattClient.setConnectionDelegate(object : GattClientConnectionDelegate {
            override suspend fun onConnectionSuccess(deviceAddress: String) {
                connectionService.onDeviceConnected(deviceAddress)
            }

            override suspend fun onConnectionFailure(deviceAddress: String, reason: String) {
                connectionService.onDeviceConnectionFailed(deviceAddress, reason)
            }
        })

        connectionService
    } bind BluetoothConnectionService::class

    single {
        AndroidCentralScanningService(
            context = androidContext(),
            coroutineScopeFacade = get(),
        ).apply {
            setOnDeviceDiscoveredCallback { deviceAddress, deviceName, rssi ->
                val connectionService = get<AndroidConnectionService>()
                kotlinx.coroutines.runBlocking {
                    connectionService.onDeviceDiscovered(deviceAddress, deviceName)
                }
            }
        }
    } binds arrayOf(CentralScanningService::class)

    single<ConnectionEventBus> {
        AndroidConnectionEventBus(
            context = androidContext(),
            coroutineScopeFacade = get(),
        )
    }

    single<AdvertisingService> {
        AndroidAdvertisingService(
            context = androidContext()
        )
    }
}
