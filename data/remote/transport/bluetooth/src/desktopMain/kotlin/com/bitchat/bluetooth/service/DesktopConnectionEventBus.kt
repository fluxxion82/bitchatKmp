package com.bitchat.bluetooth.service

import com.bitchat.domain.connectivity.eventbus.ConnectionEventBus
import com.bitchat.domain.connectivity.model.BluetoothConnectionEvent
import com.bitchat.domain.connectivity.model.ConnectionEvent
import com.bitchat.domain.connectivity.model.LocationConnectionEvent
import com.bitchat.local.bridge.NativeLocationBridge
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

internal class DesktopConnectionEventBus : ConnectionEventBus {
    override suspend fun getConnectionEvent(): Flow<ConnectionEvent> = channelFlow {
        send(ConnectionEvent.CONNECTED)
    }

    override suspend fun getBluetoothConnectionEvent(): Flow<BluetoothConnectionEvent> = channelFlow {
        send(BluetoothConnectionEvent.CONNECTED)
    }

    override suspend fun getLocationConnectionEvent(): Flow<LocationConnectionEvent> = channelFlow {
        val useNativeLocation = System.getProperty("location.native")?.lowercase() == "macos"
        val hasPermission = if (useNativeLocation && NativeLocationBridge.isAvailable()) {
            NativeLocationBridge.hasPermission()
        } else {
            true // IP-based fallback doesn't require permission
        }
        println("DesktopConnectionEventBus: location permission = $hasPermission (native=$useNativeLocation)")
        send(if (hasPermission) LocationConnectionEvent.CONNECTED else LocationConnectionEvent.DISCONNECTED)
    }
}