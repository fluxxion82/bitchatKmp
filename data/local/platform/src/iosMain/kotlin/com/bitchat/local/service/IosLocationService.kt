package com.bitchat.local.service

import com.bitchat.domain.location.model.GeoPoint
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.CoreLocation.CLAuthorizationStatus
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationCoordinate2D
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusDenied
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLAuthorizationStatusRestricted
import platform.CoreLocation.kCLLocationAccuracyHundredMeters
import platform.Foundation.NSError
import platform.darwin.NSObject
import kotlin.coroutines.resumeWithException

@OptIn(ExperimentalForeignApi::class)
class IosLocationService : LocationService {
    private val manager = CLLocationManager().apply {
        desiredAccuracy = kCLLocationAccuracyHundredMeters
        distanceFilter = 50.0
    }

    private fun authStatusString(status: CLAuthorizationStatus): String {
        return when (status) {
            kCLAuthorizationStatusNotDetermined -> "Not Determined"
            kCLAuthorizationStatusRestricted -> "Restricted"
            kCLAuthorizationStatusDenied -> "Denied"
            kCLAuthorizationStatusAuthorizedWhenInUse -> "Authorized When In Use"
            kCLAuthorizationStatusAuthorizedAlways -> "Authorized Always"
            else -> "Unknown ($status)"
        }
    }

    override suspend fun getCurrentLocation(): GeoPoint {
        return suspendCancellableCoroutine { cont ->
            var resumed = false

            val delegate = object : CLLocationManagerDelegateProtocol, NSObject() {

                override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
                    val status = manager.authorizationStatus
                    println("📍 [IosLocationService] Authorization changed: ${authStatusString(status)}")

                    // Only start location updates when authorized
                    when (status) {
                        kCLAuthorizationStatusAuthorizedWhenInUse,
                        kCLAuthorizationStatusAuthorizedAlways -> {
                            println("📍 [IosLocationService] Starting location updates (authorized)")
                            manager.startUpdatingLocation()
                        }

                        kCLAuthorizationStatusDenied,
                        kCLAuthorizationStatusRestricted -> {
                            println("📍 [IosLocationService] Permission denied/restricted")
                            if (!resumed) {
                                resumed = true
                                manager.stopUpdatingLocation()
                                cont.resumeWithException(
                                    RuntimeException("Location permission ${authStatusString(status)}")
                                )
                            }
                        }

                        else -> {
                            println("📍 [IosLocationService] Status: ${authStatusString(status)}, waiting...")
                        }
                    }
                }

                override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
                    val loc = (didUpdateLocations.lastOrNull() as? CLLocation) ?: return
                    println("📍 [IosLocationService] Got location update")

                    if (resumed) {
                        println("⚠️ [IosLocationService] Already resumed, ignoring location")
                        return
                    }
                    resumed = true
                    manager.stopUpdatingLocation()

                    val coord: CValue<CLLocationCoordinate2D> = loc.coordinate
                    val (lat, lon) = coord.useContents { latitude to longitude }

                    println("✅ [IosLocationService] Resuming with GeoPoint($lat, $lon)")
                    @Suppress("DEPRECATION")
                    cont.resume(GeoPoint(lat, lon)) {}
                }

                override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
                    println("🔴 [IosLocationService] Location error: ${didFailWithError.localizedDescription} (code: ${didFailWithError.code}, domain: ${didFailWithError.domain})")

                    if (resumed) {
                        println("⚠️ [IosLocationService] Already resumed, ignoring error")
                        return
                    }
                    resumed = true
                    manager.stopUpdatingLocation()

                    cont.resumeWithException(RuntimeException(didFailWithError.localizedDescription))
                }
            }

            manager.delegate = delegate

            val currentStatus = manager.authorizationStatus
            println("📍 [IosLocationService] getCurrentLocation() called")
            println("📍 [IosLocationService] Current auth status: ${authStatusString(currentStatus)}")

            when (currentStatus) {
                kCLAuthorizationStatusNotDetermined -> {
                    println("📍 [IosLocationService] Requesting authorization...")
                    manager.requestWhenInUseAuthorization()
                    // locationManagerDidChangeAuthorization will be called after user responds
                }

                kCLAuthorizationStatusAuthorizedWhenInUse,
                kCLAuthorizationStatusAuthorizedAlways -> {
                    println("📍 [IosLocationService] Already authorized, starting location updates")
                    manager.startUpdatingLocation()
                }

                kCLAuthorizationStatusDenied,
                kCLAuthorizationStatusRestricted -> {
                    println("📍 [IosLocationService] Permission denied/restricted, failing immediately")
                    resumed = true
                    cont.resumeWithException(
                        RuntimeException("Location permission ${authStatusString(currentStatus)}")
                    )
                }

                else -> {
                    println("📍 [IosLocationService] Unknown status, requesting authorization...")
                    manager.requestWhenInUseAuthorization()
                }
            }

            cont.invokeOnCancellation {
                println("📍 [IosLocationService] Request cancelled")
                manager.stopUpdatingLocation()
                resumed = true
            }
        }
    }

    override fun locationUpdates(): Flow<GeoPoint> = callbackFlow {
        val delegate = object : CLLocationManagerDelegateProtocol, NSObject() {
            override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
                val loc = (didUpdateLocations.lastOrNull() as? CLLocation) ?: return

                val coord: CValue<CLLocationCoordinate2D> = loc.coordinate
                val (lat, lon) = coord.useContents { latitude to longitude }
                trySend(GeoPoint(lat, lon))
            }
        }
        manager.delegate = delegate
        manager.startUpdatingLocation()
        awaitClose { manager.stopUpdatingLocation() }
    }

    override suspend fun hasLocationPermission(): Boolean {
        val status = manager.authorizationStatus
        return status == kCLAuthorizationStatusAuthorizedWhenInUse ||
                status == kCLAuthorizationStatusAuthorizedAlways
    }

    override suspend fun requestLocationPermission() {
        // iOS handles permission requests via CLLocationManager - no-op here
        // The actual request happens in getCurrentLocation() via requestWhenInUseAuthorization()
    }
}
