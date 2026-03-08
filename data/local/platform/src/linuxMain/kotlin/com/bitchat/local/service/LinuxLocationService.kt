package com.bitchat.local.service

import com.bitchat.domain.location.model.GeoPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Linux stub implementation of LocationService.
 *
 * Location services are not available on headless Linux devices.
 * For embedded devices that need location, consider:
 * - GPS hardware via serial/GPSD
 * - Manual location configuration
 * - IP-based geolocation
 */
class LinuxLocationService : LocationService {
    private val defaultLocation = GeoPoint(lat = 37.7749, lon = -122.4194)

    override suspend fun getCurrentLocation(): GeoPoint {
        // Return a default/invalid location
        // Applications should check hasLocationPermission() first
        return defaultLocation
    }

    override fun locationUpdates(): Flow<GeoPoint> {
        // No location updates available
        return emptyFlow()
    }

    override suspend fun hasLocationPermission(): Boolean {
        // Location not available on Linux embedded
        return false
    }

    override suspend fun requestLocationPermission() {
        // No-op: Location permission not applicable on Linux
    }
}
