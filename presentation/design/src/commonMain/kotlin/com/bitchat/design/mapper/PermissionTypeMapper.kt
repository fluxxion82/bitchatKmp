package com.bitchat.design.mapper

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import bitchatkmp.presentation.design.generated.resources.Res
import bitchatkmp.presentation.design.generated.resources.bluetooth_nearby_devices
import bitchatkmp.presentation.design.generated.resources.location_for_bt_scanning
import bitchatkmp.presentation.design.generated.resources.perm_location_desc
import bitchatkmp.presentation.design.generated.resources.perm_nearby_devices_desc
import bitchatkmp.presentation.design.generated.resources.perm_notifications_desc
import bitchatkmp.presentation.design.generated.resources.perm_type_camera
import bitchatkmp.presentation.design.generated.resources.perm_type_microphone
import bitchatkmp.presentation.design.generated.resources.perm_type_nearby_devices
import bitchatkmp.presentation.design.generated.resources.perm_type_notifications
import bitchatkmp.presentation.design.generated.resources.perm_type_precise_location
import com.bitchat.design.icons.Icons
import com.bitchat.design.icons.filled.Bluetooth
import com.bitchat.design.icons.filled.Camera
import com.bitchat.design.icons.filled.LocationOn
import com.bitchat.design.icons.filled.Mic
import com.bitchat.design.icons.filled.Notifications
import com.bitchat.viewvo.permissions.PermissionType
import org.jetbrains.compose.resources.stringResource

fun PermissionType.toPermissionIcon(): ImageVector {
    return when (this) {
        PermissionType.NEARBY_DEVICES -> Icons.Filled.Bluetooth
        PermissionType.PRECISE_LOCATION -> Icons.Filled.LocationOn
        PermissionType.MICROPHONE -> Icons.Filled.Mic
        PermissionType.NOTIFICATIONS -> Icons.Filled.Notifications
        PermissionType.CAMERA -> Icons.Filled.Camera
    }
}

@Composable
fun PermissionType.toPermissionText(): String {
    return when (this) {
        PermissionType.NEARBY_DEVICES -> stringResource(Res.string.perm_type_nearby_devices)
        PermissionType.PRECISE_LOCATION -> stringResource(Res.string.perm_type_precise_location)
        PermissionType.MICROPHONE -> stringResource(Res.string.perm_type_microphone)
        PermissionType.NOTIFICATIONS -> stringResource(Res.string.perm_type_notifications)
        PermissionType.CAMERA -> stringResource(Res.string.perm_type_camera)
    }
}

@Composable
fun PermissionType.toDescription(): String {
    return when (this) {
        PermissionType.NEARBY_DEVICES -> stringResource(Res.string.perm_nearby_devices_desc)
        PermissionType.PRECISE_LOCATION -> stringResource(Res.string.perm_location_desc)
        PermissionType.NOTIFICATIONS -> stringResource(Res.string.perm_notifications_desc)
        PermissionType.MICROPHONE -> ""
        PermissionType.CAMERA -> ""
    }
}

@Composable
fun PermissionType.toDisplayName(): String {
    return when (this) {
        PermissionType.NEARBY_DEVICES -> stringResource(Res.string.bluetooth_nearby_devices)
        PermissionType.PRECISE_LOCATION -> stringResource(Res.string.location_for_bt_scanning)
        PermissionType.MICROPHONE -> stringResource(Res.string.perm_type_microphone)
        PermissionType.NOTIFICATIONS -> stringResource(Res.string.perm_type_notifications)
        PermissionType.CAMERA -> stringResource(Res.string.perm_type_camera)
    }
}
