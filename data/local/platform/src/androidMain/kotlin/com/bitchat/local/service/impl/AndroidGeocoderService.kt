package com.bitchat.local.service.impl

import android.content.Context
import android.location.Address
import android.location.Geocoder
import com.bitchat.domain.location.model.GeohashChannelLevel
import com.bitchat.local.service.GeocoderService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*

class AndroidGeocoderService(
    context: Context
) : GeocoderService {
    private val geocoder = Geocoder(context, Locale.getDefault())

    override suspend fun reverseGeocode(
        lat: Double,
        lon: Double,
        level: GeohashChannelLevel
    ): String = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) {
            return@withContext ""
        }

        @Suppress("DEPRECATION")
        val addresses = geocoder.getFromLocation(lat, lon, 1)
        if (addresses.isNullOrEmpty()) {
            return@withContext ""
        }

        val address = addresses[0]
        nameForLevel(address, level).orEmpty()
    }

    override suspend fun reverseGeocodeAll(
        lat: Double,
        lon: Double
    ): Map<GeohashChannelLevel, String> = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) {
            return@withContext emptyMap()
        }

        @Suppress("DEPRECATION")
        val addresses = geocoder.getFromLocation(lat, lon, 1)
        if (addresses.isNullOrEmpty()) {
            return@withContext emptyMap()
        }

        namesByLevel(addresses[0])
    }

    private fun nameForLevel(address: Address, level: GeohashChannelLevel): String? {
        val name = when (level) {
            GeohashChannelLevel.REGION -> address.countryName
            GeohashChannelLevel.PROVINCE -> address.adminArea ?: address.subAdminArea
            GeohashChannelLevel.CITY -> address.locality ?: address.subAdminArea ?: address.adminArea
            GeohashChannelLevel.NEIGHBORHOOD -> address.subLocality ?: address.locality
            GeohashChannelLevel.BLOCK -> address.subLocality ?: address.locality
            GeohashChannelLevel.BUILDING -> address.subLocality ?: address.locality
        }

        return name?.takeIf { it.isNotBlank() }
    }

    private fun namesByLevel(address: Address): Map<GeohashChannelLevel, String> {
        val names = mutableMapOf<GeohashChannelLevel, String>()

        address.countryName?.takeIf { it.isNotBlank() }?.let {
            names[GeohashChannelLevel.REGION] = it
        }

        (address.adminArea ?: address.subAdminArea)?.takeIf { it.isNotBlank() }?.let {
            names[GeohashChannelLevel.PROVINCE] = it
        }

        (address.locality ?: address.subAdminArea ?: address.adminArea)?.takeIf { it.isNotBlank() }?.let {
            names[GeohashChannelLevel.CITY] = it
        }

        (address.subLocality ?: address.locality)?.takeIf { it.isNotBlank() }?.let {
            names[GeohashChannelLevel.NEIGHBORHOOD] = it
        }

        (address.subLocality ?: address.locality)?.takeIf { it.isNotBlank() }?.let {
            names[GeohashChannelLevel.BLOCK] = it
        }

        return names
    }
}
