package com.bitchat.local.service

import com.bitchat.domain.location.model.GeoPoint
import com.bitchat.domain.location.model.LocationFixInfo
import com.bitchat.domain.location.model.LocationUnavailableException
import com.bitchat.domain.tor.RequestedTorIntent
import com.bitchat.local.bridge.NativeLocationBridge
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.flow
import kotlin.time.AbstractLongTimeSource
import kotlin.time.DurationUnit

class JvmLocationService(
    settingsFactory: Settings.Factory,
    requestedTorIntent: RequestedTorIntent,
    ipLookup: suspend (admit: () -> Boolean) -> GeoPoint? = { admit ->
        lookUpIpLocation(admit, IpGeolocation::get)
    },
    now: () -> Long = System::currentTimeMillis,
) : LocationService {
    private val nativeEnabled = System.getProperty("location.native")?.lowercase() == "macos"
    private val nativeAvailable = if (nativeEnabled) NativeLocationBridge.init() else false
    private val source = IpLocationSource(
        settingsFactory = settingsFactory,
        requestedTorIntent = requestedTorIntent,
        ipLookup = ipLookup,
        lookupEnabled = { System.getProperty("location.iplookup")?.lowercase() != "false" },
        // The system clock, not a monotonic one: a laptop that slept has to look up again.
        timeSource = SystemClockTimeSource(now),
        wallClock = now,
    )

    override suspend fun getCurrentLocation(): GeoPoint {
        if (nativeAvailable) {
            source.rememberDeviceFix {
                NativeLocationBridge.getCurrentLocation()?.let { (lat, lon) -> GeoPoint(lat, lon) }
            }?.let { return it }
        }
        return source.fix()
            ?: throw LocationUnavailableException(source.unavailableReason(hasDeviceSource = nativeAvailable))
    }

    override fun locationUpdates() = flow { emit(getCurrentLocation()) }

    override suspend fun hasLocationPermission(): Boolean =
        if (nativeAvailable) NativeLocationBridge.hasPermission() else true

    override suspend fun requestLocationPermission() {
        if (nativeAvailable) NativeLocationBridge.requestPermission()
    }

    override suspend fun lastFixInfo(): LocationFixInfo? = source.lastFixInfo()

    override suspend fun clearCachedLocation() = source.clear()
}

internal class SystemClockTimeSource(private val now: () -> Long) : AbstractLongTimeSource(DurationUnit.MILLISECONDS) {
    override fun read(): Long = now()
}
