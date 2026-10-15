package com.bitchat.local.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.bitchat.domain.location.model.GeoPoint
import com.bitchat.domain.location.model.LocationFixInfo
import com.bitchat.domain.location.model.ObservedFix
import com.bitchat.domain.location.model.LocationUnavailableException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class AndroidLocationService(
    private val context: Context,
) : LocationService {
    private val locationManager: LocationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private var lastLocation: Location? = null

    @SuppressLint("MissingPermission")
    override suspend fun getCurrentLocation(): GeoPoint {
        Log.d(TAG, "getCurrentLocation() called")

        val lastKnownLocation = getLastKnownLocationFromProviders()
        if (lastKnownLocation != null) {
            Log.d(TAG, "Using last known location: ${lastKnownLocation.latitude}, ${lastKnownLocation.longitude}")
            lastLocation = lastKnownLocation
            return GeoPoint(lastKnownLocation.latitude, lastKnownLocation.longitude)
        }

        lastLocation?.let {
            Log.d(TAG, "Using cached location: ${it.latitude}, ${it.longitude}")
            return GeoPoint(it.latitude, it.longitude)
        }

        Log.d(TAG, "No cached location available, requesting fresh location asynchronously")
        requestFreshLocationAsync()

        // Typed so the layers above can tell an expected absence from a fault. An untyped throw
        // here reached LocationRepo's generic catch, which answered with an empty list, and the
        // channel sheet cannot distinguish that from "still looking" -- so it spun for ever and
        // logged a stack trace on every five-second poll.
        throw LocationUnavailableException(
            if (hasLocationPermission()) {
                LocationUnavailableException.Reason.LOOKUP_FAILED
            } else {
                LocationUnavailableException.Reason.PERMISSION_DENIED
            }
        )
    }

    @SuppressLint("MissingPermission")
    private fun getLastKnownLocationFromProviders(): Location? {
        val providers = locationManager.getProviders(true)
        var mostRecentLocation: Location? = null

        for (provider in providers) {
            try {
                val location = locationManager.getLastKnownLocation(provider)
                if (location != null) {
                    if (mostRecentLocation == null || location.time > mostRecentLocation.time) {
                        mostRecentLocation = location
                        Log.v(TAG, "Found location from $provider: ${location.latitude}, ${location.longitude}")
                    }
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "Security exception getting last known location from $provider: ${e.message}")
            }
        }

        return mostRecentLocation
    }

    @SuppressLint("MissingPermission")
    private fun requestFreshLocationAsync() {
        try {
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )

            var providerFound = false
            for (provider in providers) {
                if (locationManager.isProviderEnabled(provider)) {
                    Log.d(TAG, "Requesting async location from $provider")

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        locationManager.getCurrentLocation(
                            provider,
                            null,
                            context.mainExecutor,
                            { location ->
                                if (location != null) {
                                    Log.d(TAG, "Async location received from $provider: ${location.latitude}, ${location.longitude}")
                                    lastLocation = location
                                } else {
                                    Log.w(TAG, "Received null location from async getCurrentLocation")
                                }
                            }
                        )
                    } else {
                        val listener = object : LocationListener {
                            override fun onLocationChanged(location: Location) {
                                Log.d(TAG, "Async location received from $provider: ${location.latitude}, ${location.longitude}")
                                lastLocation = location
                                locationManager.removeUpdates(this)
                            }

                            @Deprecated("Deprecated in Java")
                            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {
                            }

                            override fun onProviderEnabled(provider: String) {}

                            override fun onProviderDisabled(provider: String) {}
                        }
                        locationManager.requestSingleUpdate(provider, listener, null)
                    }

                    providerFound = true
                    break
                }
            }

            if (!providerFound) {
                Log.w(TAG, "No location providers available for async request")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Async location request failed: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    override fun locationUpdates(): Flow<GeoPoint> = callbackFlow {
        Log.d(TAG, "Starting location updates")

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                Log.v(TAG, "Location update: ${location.latitude}, ${location.longitude}")
                lastLocation = location
                trySend(GeoPoint(location.latitude, location.longitude))
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {
            }

            override fun onProviderEnabled(provider: String) {
                Log.d(TAG, "Provider enabled: $provider")
            }

            override fun onProviderDisabled(provider: String) {
                Log.d(TAG, "Provider disabled: $provider")
            }
        }

        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER
        )

        for (provider in providers) {
            if (locationManager.isProviderEnabled(provider)) {
                try {
                    locationManager.requestLocationUpdates(
                        provider,
                        10_000L,
                        0f, // 0 meters
                        listener
                    )
                    Log.d(TAG, "Requested location updates from $provider")
                } catch (e: SecurityException) {
                    Log.w(TAG, "Security exception requesting updates from $provider: ${e.message}")
                }
            }
        }

        awaitClose {
            Log.d(TAG, "Stopping location updates")
            locationManager.removeUpdates(listener)
        }
    }

    override suspend fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    override suspend fun requestLocationPermission() {
        // Android handles permission requests via system dialogs - no-op here
    }

    @SuppressLint("MissingPermission")
    override suspend fun presenceFix(): ObservedFix? {
        if (!hasLocationPermission()) return null

        getLastKnownPresenceLocation()?.let(::observedDeviceFix)?.let { return it }

        val enabled = locationManager.getProviders(true)
        val provider = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        ).firstOrNull { it in enabled } ?: return null
        return requestPresenceLocation(provider)
    }

    @SuppressLint("MissingPermission")
    private fun getLastKnownPresenceLocation(): Location? {
        var newest: Location? = null
        for (provider in locationManager.getProviders(true)) {
            try {
                locationManager.getLastKnownLocation(provider)?.let { location ->
                    val latest = newest
                    if (latest == null || location.elapsedRealtimeNanos > latest.elapsedRealtimeNanos) {
                        newest = location
                    }
                }
            } catch (_: SecurityException) {
                return null
            }
        }
        return newest
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestPresenceLocation(provider: String): ObservedFix? = suspendCancellableCoroutine { continuation ->
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val signal = CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                locationManager.getCurrentLocation(provider, signal, context.mainExecutor) { location ->
                    continuation.resume(location?.let(::observedDeviceFix))
                }
            } else {
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        locationManager.removeUpdates(this)
                        continuation.resume(observedDeviceFix(location))
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
                    override fun onProviderEnabled(provider: String) = Unit
                    override fun onProviderDisabled(provider: String) = Unit
                }
                continuation.invokeOnCancellation { locationManager.removeUpdates(listener) }
                locationManager.requestSingleUpdate(provider, listener, null)
            }
        } catch (_: SecurityException) {
            continuation.resume(null)
        } catch (_: IllegalArgumentException) {
            continuation.resume(null)
        }
    }

    // Aged by the time since boot the fix carries: the OS can answer any request with a cached fix.
    private fun observedDeviceFix(location: Location): ObservedFix? {
        val ageMillis = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / NANOS_PER_MILLISECOND
        if (ageMillis !in 0..PRESENCE_MAX_AGE_MS) return null
        return ObservedFix(GeoPoint(location.latitude, location.longitude), LocationFixInfo.Source.DEVICE, ageMillis)
    }

    companion object {
        private const val TAG = "AndroidLocationService"
        private const val PRESENCE_MAX_AGE_MS = 60 * 60 * 1000L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
