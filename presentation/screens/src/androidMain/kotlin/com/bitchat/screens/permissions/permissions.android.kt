package com.bitchat.screens.permissions

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.bitchat.viewvo.permissions.PermissionStatus
import com.bitchat.viewvo.permissions.PermissionType

@Composable
actual fun createPermissionsManager(callback: PermissionCallback): PermissionsManager {
    return remember(callback) { PermissionsManager(callback) }
}

actual class PermissionsManager actual constructor(
    private val callback: PermissionCallback
) : PermissionHandler {

    @Composable
    actual override fun askPermission(permission: PermissionType) {
        askPermissions(listOf(permission))
    }

    @Composable
    actual override fun askPermissions(permissions: List<PermissionType>) {
        val context = LocalContext.current
        val androidByLogical: Map<PermissionType, List<String>> = remember(permissions) {
            permissions.associateWith { getAndroidPermissions(it) }
        }

        val allAndroidPermissions: Array<String> = remember(androidByLogical) {
            androidByLogical.values.flatten().distinct().toTypedArray()
        }

        val launcher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions()
        ) { resultMap ->
            androidByLogical.forEach { (type, requiredAndroidPerms) ->
                val accepted = isAllGrantedForLogical(context, requiredAndroidPerms, resultMap)
                callback.onPermissionStatus(type, if (accepted) PermissionStatus.GRANTED else PermissionStatus.DENIED)
            }
        }

        LaunchedEffect(allAndroidPermissions.joinToString("#")) {
            if (allAndroidPermissions.isNotEmpty()) {
                launcher.launch(allAndroidPermissions)
            } else {
                androidByLogical.forEach { (type, requiredAndroidPerms) ->
                    val accepted = isAllGrantedForLogical(context, requiredAndroidPerms, emptyMap())
                    callback.onPermissionStatus(type, if (accepted) PermissionStatus.GRANTED else PermissionStatus.DENIED)
                }
            }
        }
    }

    @Composable
    actual override fun isPermissionGranted(permission: PermissionType): Boolean {
        val context = LocalContext.current
        val androidPerms = getAndroidPermissions(permission)
        return androidPerms.all { p ->
            ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        }
    }

    @Composable
    actual override fun launchSettings() {
        val context = LocalContext.current
        val activity = context as? Activity ?: return
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", activity.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }

    private fun isAllGrantedForLogical(
        context: android.content.Context,
        androidPerms: List<String>,
        resultMap: Map<String, Boolean>
    ): Boolean {
        if (androidPerms.isEmpty()) return true

        return androidPerms.all { p ->
            val fromResult = resultMap[p]
            (fromResult ?: (ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED))
        }
    }
}

private fun getAndroidPermissions(permission: PermissionType): List<String> {
    return when (permission) {
        PermissionType.CAMERA -> {
            listOf(Manifest.permission.CAMERA)
        }

        PermissionType.NEARBY_DEVICES -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                listOf(
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN
                )
            } else {
                listOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            }
        }

        PermissionType.PRECISE_LOCATION -> {
            listOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

        PermissionType.MICROPHONE -> {
            listOf(Manifest.permission.RECORD_AUDIO)
        }

        PermissionType.NOTIFICATIONS -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                listOf(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                emptyList()
            }
        }
    }
}
