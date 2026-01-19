package com.bitchat.local.service.impl

import com.bitchat.domain.location.model.GeohashChannelLevel
import com.bitchat.local.service.GeocoderService

/**
 * Stub implementation of GeocoderService.
 * Returns placeholder location names until platform-specific implementations are added.
 */
class StubGeocoderService : GeocoderService {
    override suspend fun reverseGeocode(lat: Double, lon: Double, level: GeohashChannelLevel): String {
        // Return generic placeholder based on level
        return when (level) {
            GeohashChannelLevel.BUILDING -> "Building"
            GeohashChannelLevel.BLOCK -> "Block"
            GeohashChannelLevel.NEIGHBORHOOD -> "Neighborhood"
            GeohashChannelLevel.CITY -> "City"
            GeohashChannelLevel.PROVINCE -> "Province"
            GeohashChannelLevel.REGION -> "Region"
        }
    }

    override suspend fun reverseGeocodeAll(
        lat: Double,
        lon: Double
    ): Map<GeohashChannelLevel, String> {
        return mapOf(
            GeohashChannelLevel.BLOCK to "Block",
            GeohashChannelLevel.NEIGHBORHOOD to "Neighborhood",
            GeohashChannelLevel.CITY to "City",
            GeohashChannelLevel.PROVINCE to "Province",
            GeohashChannelLevel.REGION to "Region"
        )
    }
}
