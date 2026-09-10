package com.bitchat.local.service

import com.bitchat.domain.location.model.GeoPoint
import com.bitchat.domain.location.model.LocationFixInfo
import kotlinx.coroutines.flow.Flow

interface LocationService {
    suspend fun getCurrentLocation(): GeoPoint
    fun locationUpdates(): Flow<GeoPoint>
    suspend fun hasLocationPermission(): Boolean
    suspend fun requestLocationPermission()

    /**
     * Provenance of the fix [getCurrentLocation] last returned, or null when it is not known.
     *
     * Defaulted so a platform that only ever reports live device fixes needs no change; the ones
     * that can serve a cached or IP-derived answer override it.
     */
    suspend fun lastFixInfo(): LocationFixInfo? = null

    /**
     * Drop any cached or persisted fix. Called from the clear-all-data path, which otherwise leaves
     * coordinates on disk after an operation that claims to delete everything.
     */
    suspend fun clearCachedLocation() {}
}
