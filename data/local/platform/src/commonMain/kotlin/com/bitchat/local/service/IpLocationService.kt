package com.bitchat.local.service

import com.bitchat.domain.location.model.GeoPoint
import com.bitchat.domain.location.model.LocationFixInfo
import com.bitchat.domain.location.model.LocationUnavailableException
import com.bitchat.domain.location.model.SavedPlace
import com.bitchat.domain.tor.RequestedTorIntent
import com.bitchat.local.prefs.GeohashPreferences
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.flow

/** A device without location hardware uses its address's city while Tor is off, else its last fix. */
class IpLocationService internal constructor(private val source: IpLocationSource) : LocationService {
    /** [environment] reads an environment variable; [httpGet] is one GET from this device's own address. */
    constructor(
        settingsFactory: Settings.Factory,
        requestedTorIntent: RequestedTorIntent,
        environment: (String) -> String?,
        httpGet: suspend (url: String, admit: () -> Boolean) -> String?,
        placeSaved: () -> Boolean = { false },
    ) : this(
        IpLocationSource(
            settingsFactory = settingsFactory,
            requestedTorIntent = requestedTorIntent,
            ipLookup = { admit -> lookUpIpLocation(admit, httpGet) },
            // libcurl takes a proxy from the environment when the client names none.
            lookupEnabled = { !proxyNamedInEnvironment(environment) && !placeSaved() },
        ),
    )

    override suspend fun getCurrentLocation(): GeoPoint = source.fix()
        ?: throw LocationUnavailableException(source.unavailableReason(hasDeviceSource = false))

    override fun locationUpdates() = flow { emit(getCurrentLocation()) }

    // A board keeps counting as unable to be where its channel is.
    override suspend fun hasLocationPermission() = false

    override suspend fun requestLocationPermission() = Unit

    override suspend fun lastFixInfo(): LocationFixInfo? = source.lastFixInfo()

    override suspend fun clearCachedLocation() = source.clear()
}

internal fun GeohashPreferences.placeSaved(): Boolean = getSavedPlace()?.let(SavedPlace::of) != null
