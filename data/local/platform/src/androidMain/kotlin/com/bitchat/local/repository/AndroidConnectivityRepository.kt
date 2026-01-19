package com.bitchat.local.repository

import com.bitchat.domain.connectivity.eventbus.ConnectionEventBus
import com.bitchat.domain.connectivity.model.BluetoothConnectionEvent
import com.bitchat.domain.connectivity.model.LocationConnectionEvent
import com.bitchat.domain.connectivity.repository.ConnectivityRepository
import kotlinx.coroutines.flow.first

class AndroidConnectivityRepository(
    private val connectEventBus: ConnectionEventBus,
) : ConnectivityRepository {

    override suspend fun isBluetoothEnabled(): Boolean {
        val event = connectEventBus.getBluetoothConnectionEvent().first()
        println("isBluetoothEnabled: $event")
        return when (event) {
            BluetoothConnectionEvent.CONNECTED -> true
            BluetoothConnectionEvent.DISCONNECTED -> false
        }
    }

    override suspend fun isLocationServicesEnabled(): Boolean {
        val event = connectEventBus.getLocationConnectionEvent().first()
        println("isLocationServicesEnabled: $event")
        return when (event) {
            LocationConnectionEvent.CONNECTED -> true
            LocationConnectionEvent.DISCONNECTED -> false
        }
    }
}
