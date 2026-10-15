package com.bitchat.local.service

import com.bitchat.domain.location.model.GeoPoint
import com.bitchat.domain.location.model.LocationFixInfo
import com.bitchat.domain.location.model.LocationUnavailableException
import com.bitchat.domain.location.model.ObservedFix
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import platform.Foundation.timeIntervalSinceNow
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.resume

@OptIn(ExperimentalForeignApi::class)
class IosLocationService : LocationService {
    private val presenceMutex = Mutex()
    // Made and used on the main queue only: CoreLocation calls the delegate on the thread that made it.
    private val manager by lazy {
        CLLocationManager().apply {
            desiredAccuracy = kCLLocationAccuracyHundredMeters
            distanceFilter = 50.0
        }
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

    override suspend fun getCurrentLocation(): GeoPoint = withContext(Dispatchers.Main) { requestLocation() }

    private suspend fun requestLocation(): GeoPoint {
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
                                    LocationUnavailableException(
                                        LocationUnavailableException.Reason.PERMISSION_DENIED
                                    )
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

                    // The CoreLocation message is kept as the cause so it stays in the log; the
                    // reason is what reaches the user.
                    cont.resumeWithException(
                        LocationUnavailableException(
                            LocationUnavailableException.Reason.LOOKUP_FAILED,
                            RuntimeException(didFailWithError.localizedDescription)
                        )
                    )
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
                        LocationUnavailableException(
                            LocationUnavailableException.Reason.PERMISSION_DENIED,
                            RuntimeException("Location permission ${authStatusString(currentStatus)}")
                        )
                    )
                }

                else -> {
                    println("📍 [IosLocationService] Unknown status, requesting authorization...")
                    manager.requestWhenInUseAuthorization()
                }
            }

            cont.invokeOnCancellation {
                println("📍 [IosLocationService] Request cancelled")
                dispatch_async(dispatch_get_main_queue()) {
                    manager.stopUpdatingLocation()
                    resumed = true
                }
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
    }.flowOn(Dispatchers.Main)

    override suspend fun hasLocationPermission(): Boolean = withContext(Dispatchers.Main) {
        val status = manager.authorizationStatus
        status == kCLAuthorizationStatusAuthorizedWhenInUse ||
            status == kCLAuthorizationStatusAuthorizedAlways
    }

    override suspend fun requestLocationPermission() {
        // iOS handles permission requests via CLLocationManager - no-op here
        // The actual request happens in getCurrentLocation() via requestWhenInUseAuthorization()
    }

    override suspend fun presenceFix(): ObservedFix? = presenceMutex.withLock {
        withContext(Dispatchers.Main) { readPresenceFix() }
    }

    private suspend fun readPresenceFix(): ObservedFix? {
        val presenceManager = CLLocationManager().apply {
            desiredAccuracy = kCLLocationAccuracyHundredMeters
        }
        val status = presenceManager.authorizationStatus
        if (status != kCLAuthorizationStatusAuthorizedWhenInUse && status != kCLAuthorizationStatusAuthorizedAlways) {
            return null
        }
        presenceManager.location?.let(::observedDeviceFix)?.let { return it }

        return suspendCancellableCoroutine { continuation ->
            var completed = false
            var delegate: CLLocationManagerDelegateProtocol? = null

            fun complete(value: ObservedFix?) {
                if (completed) return
                completed = true
                presenceManager.stopUpdatingLocation()
                presenceManager.delegate = null
                delegate = null
                continuation.resume(value)
            }

            delegate = object : CLLocationManagerDelegateProtocol, NSObject() {
                override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
                    (didUpdateLocations.lastOrNull() as? CLLocation)?.let(::observedDeviceFix)?.let(::complete)
                        ?: complete(null)
                }

                override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
                    complete(null)
                }
            }
            presenceManager.delegate = delegate
            presenceManager.requestLocation()
            continuation.invokeOnCancellation {
                dispatch_async(dispatch_get_main_queue()) {
                    if (!completed) {
                        completed = true
                        presenceManager.stopUpdatingLocation()
                        presenceManager.delegate = null
                        delegate = null
                    }
                }
            }
        }
    }

    private fun observedDeviceFix(location: CLLocation): ObservedFix? {
        val ageMillis = (-location.timestamp.timeIntervalSinceNow * 1_000).toLong()
        if (ageMillis !in 0..PRESENCE_MAX_AGE_MS) return null
        val coordinate = location.coordinate
        val (lat, lon) = coordinate.useContents { latitude to longitude }
        return ObservedFix(GeoPoint(lat, lon), LocationFixInfo.Source.DEVICE, ageMillis)
    }

    private companion object {
        const val PRESENCE_MAX_AGE_MS = 60 * 60 * 1000L
    }
}
