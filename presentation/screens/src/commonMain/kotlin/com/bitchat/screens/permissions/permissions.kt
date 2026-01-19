package com.bitchat.screens.permissions

import androidx.compose.runtime.Composable
import com.bitchat.viewvo.permissions.PermissionStatus
import com.bitchat.viewvo.permissions.PermissionType

expect class PermissionsManager(callback: PermissionCallback) : PermissionHandler {
    @Composable
    override fun askPermission(permission: PermissionType)

    @Composable
    override fun askPermissions(permissions: List<PermissionType>)

    @Composable
    override fun isPermissionGranted(permission: PermissionType): Boolean

    @Composable
    override fun launchSettings()
}

interface PermissionCallback {
    fun onPermissionStatus(permissionType: PermissionType, status: PermissionStatus)
}

@Composable
expect fun createPermissionsManager(callback: PermissionCallback): PermissionsManager

interface PermissionHandler {
    @Composable
    fun askPermission(permission: PermissionType)

    @Composable
    fun askPermissions(permissions: List<PermissionType>)

    @Composable
    fun isPermissionGranted(permission: PermissionType): Boolean

    @Composable
    fun launchSettings()
}
