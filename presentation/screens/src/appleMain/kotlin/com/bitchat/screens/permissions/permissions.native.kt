package com.bitchat.screens.permissions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.bitchat.viewvo.permissions.PermissionStatus
import com.bitchat.viewvo.permissions.PermissionType
import platform.AVFoundation.AVAuthorizationStatus
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusDenied
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVAuthorizationStatusRestricted
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.CoreLocation.CLAuthorizationStatus
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusDenied
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLAuthorizationStatusRestricted
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusDenied
import platform.UserNotifications.UNAuthorizationStatusNotDetermined
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNUserNotificationCenter
import platform.darwin.NSObject

@Composable
actual fun createPermissionsManager(callback: PermissionCallback): PermissionsManager {
    return remember { PermissionsManager(callback) }
}

actual class PermissionsManager actual constructor(private val callback: PermissionCallback) : PermissionHandler {
    @Composable
    private fun currentCallback(): PermissionCallback {
        val current by rememberUpdatedState(callback)
        return current
    }

    @Composable
    actual override fun askPermission(permission: PermissionType) {
        val cb = currentCallback()
        when (permission) {
            PermissionType.CAMERA -> {
                LaunchedEffect(Unit) {
                    val status = AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)
                    handleAVPermission(status, AVMediaTypeVideo, permission, cb)
                }
            }

            PermissionType.MICROPHONE -> {
                LaunchedEffect(Unit) {
                    val status = AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeAudio)
                    handleAVPermission(status, AVMediaTypeAudio, permission, cb)
                }
            }

            PermissionType.PRECISE_LOCATION -> {
                val locationManager = remember { CLLocationManager() }
                val status = remember {
                    CLLocationManager.authorizationStatus()
                }
                LaunchedEffect(status) {
                    handleLocationPermission(
                        status = status,
                        locationManager = locationManager,
                        permissionType = permission
                    )
                }
            }

            PermissionType.NOTIFICATIONS -> {
                val cb = currentCallback()
                LaunchedEffect(Unit) {
                    val lm = CLLocationManager()
                    val delegate = AuthDelegate(PermissionType.PRECISE_LOCATION, cb)
                    lm.delegate = delegate

                    val status = CLLocationManager.authorizationStatus()
                    when (status) {
                        kCLAuthorizationStatusAuthorizedAlways,
                        kCLAuthorizationStatusAuthorizedWhenInUse ->
                            cb.onPermissionStatus(PermissionType.PRECISE_LOCATION, PermissionStatus.GRANTED)

                        kCLAuthorizationStatusNotDetermined -> {
                            lm.requestWhenInUseAuthorization()
                        }

                        kCLAuthorizationStatusDenied, kCLAuthorizationStatusRestricted ->
                            cb.onPermissionStatus(PermissionType.PRECISE_LOCATION, PermissionStatus.DENIED)

                        else -> cb.onPermissionStatus(PermissionType.PRECISE_LOCATION, PermissionStatus.DENIED)
                    }
                }
            }

            PermissionType.NEARBY_DEVICES -> {
                // iOS doesn't have a direct equivalent to Android's NEARBY_DEVICES
                // Bluetooth permissions are handled differently:
                // - Central (scanning): No permission needed, just use
                // - Peripheral (advertising): Automatic prompt when needed
                // For now, we'll report as granted since there's no explicit permission to request
                LaunchedEffect(Unit) {
                    callback.onPermissionStatus(permission, PermissionStatus.GRANTED)
                }
            }
        }
    }

    @Composable
    actual override fun askPermissions(permissions: List<PermissionType>) {

    }

    private fun handleAVPermission(
        status: AVAuthorizationStatus,
        mediaType: String?,
        permissionType: PermissionType,
        cb: PermissionCallback,
    ) {
        when (status) {
            AVAuthorizationStatusAuthorized -> cb.onPermissionStatus(permissionType, PermissionStatus.GRANTED)
            AVAuthorizationStatusNotDetermined -> {
                AVCaptureDevice.requestAccessForMediaType(mediaType) { granted ->
                    cb.onPermissionStatus(permissionType, if (granted) PermissionStatus.GRANTED else PermissionStatus.DENIED)
                }
            }

            AVAuthorizationStatusDenied, AVAuthorizationStatusRestricted -> cb.onPermissionStatus(permissionType, PermissionStatus.DENIED)
            else -> cb.onPermissionStatus(permissionType, PermissionStatus.DENIED)
        }
    }

    private fun handleLocationPermission(
        status: CLAuthorizationStatus,
        locationManager: CLLocationManager,
        permissionType: PermissionType
    ) {
        when (status) {
            kCLAuthorizationStatusAuthorizedAlways,
            kCLAuthorizationStatusAuthorizedWhenInUse -> {
                callback.onPermissionStatus(permissionType, PermissionStatus.GRANTED)
            }

            kCLAuthorizationStatusNotDetermined -> {
                // Request "When In Use" authorization
                // Note: You need to add NSLocationWhenInUseUsageDescription to Info.plist
                locationManager.requestWhenInUseAuthorization()
                // The actual result will be delivered via CLLocationManagerDelegate
                // For simplicity, we're not implementing the delegate pattern here
                // In production, you'd want to set up a delegate to get the actual result
                callback.onPermissionStatus(permissionType, PermissionStatus.DENIED)
            }

            kCLAuthorizationStatusDenied, kCLAuthorizationStatusRestricted -> {
                callback.onPermissionStatus(permissionType, PermissionStatus.DENIED)
            }

            else -> {
                callback.onPermissionStatus(permissionType, PermissionStatus.DENIED)
            }
        }
    }

    private fun handleNotificationPermission(permissionType: PermissionType) {
        val center = UNUserNotificationCenter.currentNotificationCenter()

        center.getNotificationSettingsWithCompletionHandler { settings ->
            when (settings?.authorizationStatus) {
                UNAuthorizationStatusAuthorized, UNAuthorizationStatusProvisional -> {
                    callback.onPermissionStatus(permissionType, PermissionStatus.GRANTED)
                }

                UNAuthorizationStatusNotDetermined -> {
                    val options = UNAuthorizationOptionAlert or
                            UNAuthorizationOptionSound or
                            UNAuthorizationOptionBadge

                    center.requestAuthorizationWithOptions(options) { isGranted, error ->
                        if (isGranted) {
                            callback.onPermissionStatus(permissionType, PermissionStatus.GRANTED)
                        } else {
                            callback.onPermissionStatus(permissionType, PermissionStatus.DENIED)
                        }
                    }
                }

                UNAuthorizationStatusDenied -> {
                    callback.onPermissionStatus(permissionType, PermissionStatus.DENIED)
                }

                else -> {
                    callback.onPermissionStatus(permissionType, PermissionStatus.DENIED)
                }
            }
        }
    }

    @Composable
    actual override fun isPermissionGranted(permission: PermissionType): Boolean {
        return when (permission) {
            PermissionType.CAMERA ->
                AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo) == AVAuthorizationStatusAuthorized

            PermissionType.MICROPHONE ->
                AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeAudio) == AVAuthorizationStatusAuthorized

            PermissionType.PRECISE_LOCATION -> {
                val status = remember {
                    CLLocationManager.authorizationStatus()
                }
                status == kCLAuthorizationStatusAuthorizedAlways ||
                        status == kCLAuthorizationStatusAuthorizedWhenInUse
            }

            PermissionType.NOTIFICATIONS -> {
                // TODO: Implement proper UNUserNotificationCenter check
                // For now, return false to avoid using wrong permission status
                false
            }

            PermissionType.NEARBY_DEVICES -> {
                // iOS doesn't require explicit permission for Bluetooth scanning
                true
            }
        }
    }

    @Composable
    actual override fun launchSettings() {
        LaunchedEffect(Unit) {
            NSURL.URLWithString(UIApplicationOpenSettingsURLString)?.let { url ->
                UIApplication.sharedApplication.openURL(url)
            }
        }
    }
}

private class AuthDelegate(
    private val permissionType: PermissionType,
    private val cb: PermissionCallback
) : NSObject(), CLLocationManagerDelegateProtocol {

    override fun locationManager(manager: CLLocationManager, didChangeAuthorizationStatus: CLAuthorizationStatus) {
        when (didChangeAuthorizationStatus) {
            kCLAuthorizationStatusAuthorizedAlways,
            kCLAuthorizationStatusAuthorizedWhenInUse -> cb.onPermissionStatus(permissionType, PermissionStatus.GRANTED)

            kCLAuthorizationStatusDenied, kCLAuthorizationStatusRestricted -> cb.onPermissionStatus(permissionType, PermissionStatus.DENIED)
            else -> Unit
        }
    }
}
