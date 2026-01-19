package com.bitchat.local.service

import com.bitchat.domain.location.model.GeoPoint
import kotlinx.coroutines.flow.Flow

interface LocationService {
    suspend fun getCurrentLocation(): GeoPoint
    fun locationUpdates(): Flow<GeoPoint>
    suspend fun hasLocationPermission(): Boolean
    suspend fun requestLocationPermission()
}
