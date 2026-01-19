package com.bitchat.local.service

import com.bitchat.domain.location.model.GeohashChannelLevel

interface GeocoderService {
    suspend fun reverseGeocode(lat: Double, lon: Double, level: GeohashChannelLevel): String
    suspend fun reverseGeocodeAll(lat: Double, lon: Double): Map<GeohashChannelLevel, String>
}
