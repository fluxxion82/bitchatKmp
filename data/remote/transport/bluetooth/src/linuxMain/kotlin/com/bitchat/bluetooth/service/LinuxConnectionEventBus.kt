package com.bitchat.bluetooth.service

import com.bitchat.domain.connectivity.eventbus.ConnectionEventBus
import com.bitchat.domain.connectivity.model.BluetoothConnectionEvent
import com.bitchat.domain.connectivity.model.ConnectionEvent
import com.bitchat.domain.connectivity.model.LocationConnectionEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Linux stub implementation of ConnectionEventBus.
 *
 * Returns flows with DISCONNECTED state since Bluetooth/Location
 * services are not available on headless Linux by default.
 */
class LinuxConnectionEventBus : ConnectionEventBus {
    override suspend fun getConnectionEvent(): Flow<ConnectionEvent> {
        return flowOf(ConnectionEvent.CONNECTED)
    }

    override suspend fun getBluetoothConnectionEvent(): Flow<BluetoothConnectionEvent> {
        return flowOf(BluetoothConnectionEvent.CONNECTED)
    }

    override suspend fun getLocationConnectionEvent(): Flow<LocationConnectionEvent> {
        return flowOf(LocationConnectionEvent.CONNECTED)
    }
}
