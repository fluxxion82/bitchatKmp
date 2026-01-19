package com.bitchat.repo.location

import com.bitchat.domain.location.model.GeohashChannelLevel
import com.bitchat.local.service.GeocoderService
import com.bitchat.nostr.util.GeohashUtils

private val allowedChars = "0123456789bcdefghjkmnpqrstuvwxyz".toSet()

internal suspend fun resolveBookmarkName(
    geohash: String,
    geocoder: GeocoderService
): String? {
    val normalized = normalizeGeohash(geohash)
    if (normalized.isEmpty()) {
        return null
    }

    return if (normalized.length <= 2) {
        resolveCompositeAdminName(normalized, geocoder)
    } else {
        resolveSinglePointName(normalized, geocoder)
    }
}

internal fun compositeNameForCandidates(candidates: List<String>): String? {
    return when (candidates.size) {
        0 -> null
        1 -> candidates.first()
        else -> "${candidates[0]} and ${candidates[1]}"
    }
}

private fun normalizeGeohash(raw: String): String {
    return raw.trim()
        .lowercase()
        .replace("#", "")
        .filter { it in allowedChars }
}

private suspend fun resolveCompositeAdminName(
    geohash: String,
    geocoder: GeocoderService
): String? {
    val bounds = GeohashUtils.decodeToBoundsOrNull(geohash) ?: return null
    val points = listOf(
        bounds.centerLat to bounds.centerLon,
        bounds.latMin to bounds.lonMin,
        bounds.latMin to bounds.lonMax,
        bounds.latMax to bounds.lonMin,
        bounds.latMax to bounds.lonMax
    )

    val names = linkedSetOf<String>()
    for ((lat, lon) in points) {
        val name = resolveAdminOrCountry(lat, lon, geocoder)
        if (!name.isNullOrBlank()) {
            names.add(name)
        }
        if (names.size >= 2) {
            break
        }
    }

    return compositeNameForCandidates(names.toList())
}

private suspend fun resolveSinglePointName(
    geohash: String,
    geocoder: GeocoderService
): String? {
    val (lat, lon) = GeohashUtils.decodeToCenterOrNull(geohash) ?: return null
    val levels = fallbackLevelsForLength(geohash.length)
    return reverseGeocodeFirstMatch(lat, lon, levels, geocoder)
}

private fun fallbackLevelsForLength(length: Int): List<GeohashChannelLevel> {
    return when (length) {
        in 3..4 -> listOf(GeohashChannelLevel.PROVINCE, GeohashChannelLevel.REGION)
        5 -> listOf(GeohashChannelLevel.CITY)
        in 6..7 -> listOf(GeohashChannelLevel.NEIGHBORHOOD, GeohashChannelLevel.CITY)
        else -> listOf(
            GeohashChannelLevel.NEIGHBORHOOD,
            GeohashChannelLevel.CITY,
            GeohashChannelLevel.PROVINCE,
            GeohashChannelLevel.REGION
        )
    }
}

private suspend fun resolveAdminOrCountry(
    lat: Double,
    lon: Double,
    geocoder: GeocoderService
): String? {
    return reverseGeocodeFirstMatch(
        lat,
        lon,
        listOf(GeohashChannelLevel.PROVINCE, GeohashChannelLevel.REGION),
        geocoder
    )
}

private suspend fun reverseGeocodeFirstMatch(
    lat: Double,
    lon: Double,
    levels: List<GeohashChannelLevel>,
    geocoder: GeocoderService
): String? {
    for (level in levels) {
        val name = geocoder.reverseGeocode(lat, lon, level).trim()
        if (name.isNotBlank()) {
            return name
        }
    }
    return null
}
