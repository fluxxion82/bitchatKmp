package com.bitchat.local.nativebridge

import com.bitchat.local.util.CoreLocationRunLoop
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreLocation.CLAuthorizationStatus
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationCoordinate2D
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusDenied
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLAuthorizationStatusRestricted
import platform.CoreLocation.kCLLocationAccuracyHundredMeters
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.timeIntervalSince1970
import platform.darwin.NSObject
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicReference

@OptIn(ExperimentalForeignApi::class)
internal object MacLocationController {
    private val locationResult = AtomicReference<Pair<Double, Double>?>(null)
    private val locationError = AtomicReference<String?>(null)
    private val requestComplete = AtomicInt(0)

    // Manager and delegate created lazily
    private var delegateInstance: LocationDelegate? = null
    private var managerInstance: CLLocationManager? = null

    private val manager: CLLocationManager
        get() {
            if (managerInstance == null) {
                CoreLocationRunLoop.ensure()
                delegateInstance = LocationDelegate()
                managerInstance = CLLocationManager().apply {
                    desiredAccuracy = kCLLocationAccuracyHundredMeters
                    distanceFilter = 50.0
                    delegate = delegateInstance
                }
            }
            return managerInstance!!
        }

    // Delegate as a class, not an object
    private class LocationDelegate : NSObject(), CLLocationManagerDelegateProtocol {
        override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
            val status = manager.authorizationStatus
            println("[MacLocationController] Authorization changed: ${authStatusString(status)}")

            when (status) {
                kCLAuthorizationStatusAuthorizedAlways -> {
                    println("[MacLocationController] Authorized, starting location updates")
                    manager.startUpdatingLocation()
                }
                kCLAuthorizationStatusDenied,
                kCLAuthorizationStatusRestricted -> {
                    println("[MacLocationController] Permission denied/restricted")
                    locationError.value = "Location permission ${authStatusString(status)}"
                    requestComplete.value = 1
                }
                else -> {
                    println("[MacLocationController] Status: ${authStatusString(status)}, waiting...")
                }
            }
        }

        override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
            val loc = (didUpdateLocations.lastOrNull() as? CLLocation) ?: return
            println("[MacLocationController] Got location update")

            manager.stopUpdatingLocation()

            val coord: CValue<CLLocationCoordinate2D> = loc.coordinate
            val (lat, lon) = coord.useContents { latitude to longitude }

            println("[MacLocationController] Location: ($lat, $lon)")
            locationResult.value = lat to lon
            requestComplete.value = 1
        }

        override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
            println("[MacLocationController] Location error: ${didFailWithError.localizedDescription}")
            manager.stopUpdatingLocation()
            locationError.value = didFailWithError.localizedDescription
            requestComplete.value = 1
        }
    }

    private fun authStatusString(status: CLAuthorizationStatus): String {
        return when (status) {
            kCLAuthorizationStatusNotDetermined -> "Not Determined"
            kCLAuthorizationStatusRestricted -> "Restricted"
            kCLAuthorizationStatusDenied -> "Denied"
            kCLAuthorizationStatusAuthorizedAlways -> "Authorized Always"
            else -> "Unknown ($status)"
        }
    }

    fun getCurrentLocation(): Pair<Double, Double>? {
        // Reset state
        locationResult.value = null
        locationError.value = null
        requestComplete.value = 0

        val currentStatus = manager.authorizationStatus
        println("[MacLocationController] getCurrentLocation() called, status: ${authStatusString(currentStatus)}")

        when (currentStatus) {
            kCLAuthorizationStatusNotDetermined -> {
                println("[MacLocationController] Requesting authorization...")
                // Note: On macOS, we need to request "Always" authorization
                // "WhenInUse" is not available on macOS
                manager.requestAlwaysAuthorization()
            }
            kCLAuthorizationStatusAuthorizedAlways -> {
                println("[MacLocationController] Already authorized, starting location updates")
                manager.startUpdatingLocation()
            }
            kCLAuthorizationStatusDenied,
            kCLAuthorizationStatusRestricted -> {
                println("[MacLocationController] Permission denied/restricted")
                return null
            }
            else -> {
                println("[MacLocationController] Unknown status, requesting authorization...")
                manager.requestAlwaysAuthorization()
            }
        }

        // Wait for location with timeout (10 seconds)
        val startTime = NSDate().timeIntervalSince1970
        val timeout = 10.0

        while (requestComplete.value == 0) {
            val elapsed = NSDate().timeIntervalSince1970 - startTime
            if (elapsed > timeout) {
                println("[MacLocationController] Timeout waiting for location")
                manager.stopUpdatingLocation()
                return null
            }
            // Sleep briefly to allow delegate callbacks on the runloop thread
            platform.posix.usleep(100_000u) // 100ms
        }

        locationError.value?.let {
            println("[MacLocationController] Error: $it")
            return null
        }

        return locationResult.value
    }

    fun hasPermission(): Boolean {
        val status = manager.authorizationStatus
        return status == kCLAuthorizationStatusAuthorizedAlways
    }

    fun requestPermission() {
        val status = manager.authorizationStatus
        if (status == kCLAuthorizationStatusNotDetermined) {
            manager.requestAlwaysAuthorization()
        }
    }
}
