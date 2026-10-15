package com.bitchat.repo.location

import com.bitchat.domain.location.model.LocationFixInfo
import com.bitchat.domain.location.model.ObservedFix
import com.bitchat.domain.location.model.SavedPlace
import com.bitchat.nostr.util.GeohashUtils

internal const val PRESENCE_MAX_AGE_MS = 60 * 60 * 1000L
internal const val IP_PRESENCE_MAX_PRECISION = 5

internal fun isPresent(channel: String, saved: SavedPlace?, fix: ObservedFix?): Boolean {
    val normalized = SavedPlace.of(channel)?.geohash ?: return false
    saved?.let { return normalized.length <= it.geohash.length && it.geohash.startsWith(normalized) }

    val observed = fix ?: return false
    if (observed.ageMillis !in 0..PRESENCE_MAX_AGE_MS) return false
    if (observed.source != LocationFixInfo.Source.DEVICE && observed.source != LocationFixInfo.Source.IP_ADDRESS) return false
    if (observed.source == LocationFixInfo.Source.IP_ADDRESS && normalized.length > IP_PRESENCE_MAX_PRECISION) return false

    return GeohashUtils.encodeOrNull(observed.point.lat, observed.point.lon, normalized.length) == normalized
}
