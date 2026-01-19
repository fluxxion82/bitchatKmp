package com.bitchat.domain.location.model

import kotlinx.serialization.Serializable

data class GeohashChannel(
    val level: GeohashChannelLevel,
    val geohash: String
) {
    val id: String get() = "${level.name}-$geohash"

    val displayName: String get() = "${level.displayName} • $geohash"
}

@Serializable
data class GeoPoint(val lat: Double, val lon: Double)
