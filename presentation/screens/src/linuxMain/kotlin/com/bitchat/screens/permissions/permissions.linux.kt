package com.bitchat.screens.permissions

import androidx.compose.runtime.Composable
import com.bitchat.viewvo.permissions.PermissionStatus
import com.bitchat.viewvo.permissions.PermissionType

/**
 * Linux stub implementation of PermissionsManager.
 *
 * On embedded Linux, permissions are typically handled at the OS/system level
 * rather than at runtime. All permissions are reported as granted.
 */
actual class PermissionsManager actual constructor(
    private val callback: PermissionCallback
) : PermissionHandler {

    @Composable
    actual override fun askPermission(permission: PermissionType) {
        // On Linux, permissions are granted at system level
        callback.onPermissionStatus(permission, PermissionStatus.GRANTED)
    }

    @Composable
    actual override fun askPermissions(permissions: List<PermissionType>) {
        permissions.forEach { permission ->
            callback.onPermissionStatus(permission, PermissionStatus.GRANTED)
        }
    }

    @Composable
    actual override fun isPermissionGranted(permission: PermissionType): Boolean {
        // Assume all permissions are granted on Linux
        return true
    }

    @Composable
    actual override fun launchSettings() {
        // No-op on Linux - no settings app to launch
    }
}

@Composable
actual fun createPermissionsManager(callback: PermissionCallback): PermissionsManager {
    return PermissionsManager(callback)
}
