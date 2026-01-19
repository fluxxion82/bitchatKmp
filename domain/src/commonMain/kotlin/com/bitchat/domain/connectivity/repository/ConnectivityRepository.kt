package com.bitchat.domain.connectivity.repository

interface ConnectivityRepository {
    suspend fun isBluetoothEnabled(): Boolean
    suspend fun isLocationServicesEnabled(): Boolean
}
