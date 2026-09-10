package com.bitchat.local.service

import com.bitchat.domain.location.model.GeoPoint
import com.bitchat.domain.location.model.LocationUnavailableException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Linux stub implementation of LocationService.
 *
 * Location services are not available on headless Linux devices. This reported a hardcoded San
 * Francisco coordinate instead of admitting that, which would have put an embedded node into a
 * geohash channel for a city it is nowhere near. For a device that genuinely needs a fix,
 * consider GPS hardware over serial/GPSD, or a manually configured coordinate.
 */
class LinuxLocationService : LocationService {

    override suspend fun getCurrentLocation(): GeoPoint {
        throw LocationUnavailableException(LocationUnavailableException.Reason.NO_SOURCE)
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
