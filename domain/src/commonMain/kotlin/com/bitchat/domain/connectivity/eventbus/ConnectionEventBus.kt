package com.bitchat.domain.connectivity.eventbus

import com.bitchat.domain.connectivity.model.BluetoothConnectionEvent
import com.bitchat.domain.connectivity.model.ConnectionEvent
import com.bitchat.domain.connectivity.model.LocationConnectionEvent
import kotlinx.coroutines.flow.Flow

interface ConnectionEventBus {
    suspend fun getConnectionEvent(): Flow<ConnectionEvent>
    suspend fun getBluetoothConnectionEvent(): Flow<BluetoothConnectionEvent>
    suspend fun getLocationConnectionEvent(): Flow<LocationConnectionEvent>
}
