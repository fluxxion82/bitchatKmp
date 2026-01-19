package com.bitchat.local.service

import com.bitchat.domain.location.model.GeoPoint
import com.bitchat.local.nativebridge.MacLocationController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class MacosLocationService : LocationService {
    override suspend fun getCurrentLocation(): GeoPoint {
        val location = MacLocationController.getCurrentLocation()
            ?: throw RuntimeException("Unable to get location - permission denied or location services disabled")
        return GeoPoint(location.first, location.second)
    }

    override fun locationUpdates(): Flow<GeoPoint> = flow {
        emit(getCurrentLocation())
    }

    override suspend fun hasLocationPermission(): Boolean {
        return MacLocationController.hasPermission()
    }

    override suspend fun requestLocationPermission() {
        MacLocationController.requestPermission()
    }
}
