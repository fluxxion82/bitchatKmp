package com.bitchat.screens.permissions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.bitchat.viewmodel.permissions.PermissionsViewModel
import com.bitchat.viewvo.permissions.PermissionStatus
import com.bitchat.viewvo.permissions.PermissionType
import org.koin.compose.viewmodel.koinViewModel

actual class PermissionsManager actual constructor(
    private val callback: PermissionCallback
) : PermissionHandler {

    private var viewModel: PermissionsViewModel? = null

    fun setViewModel(vm: PermissionsViewModel) {
        viewModel = vm
    }

    @Composable
    actual override fun askPermission(permission: PermissionType) {
        val vm = viewModel ?: return

        LaunchedEffect(permission) {
            when (permission) {
                PermissionType.PRECISE_LOCATION -> {
                    vm.requestLocationPermission()
                }
                else -> {
                    callback.onPermissionStatus(permission, PermissionStatus.GRANTED)
                }
            }
        }
    }

    @Composable
    actual override fun isPermissionGranted(permission: PermissionType): Boolean {
        val vm = viewModel

        return when (permission) {
            PermissionType.PRECISE_LOCATION -> {
                if (vm != null) {
                    remember {
                        kotlinx.coroutines.runBlocking {
                            vm.isLocationPermissionGranted()
                        }
                    }
                } else {
                    true
                }
            }
            else -> true
        }
    }

    @Composable
    actual override fun askPermissions(permissions: List<PermissionType>) {
        permissions.forEach { permission ->
            askPermission(permission)
        }
    }

    @Composable
    actual override fun launchSettings() {
        LaunchedEffect(Unit) {
            openSystemSettings()
        }
    }

    private fun openSystemSettings() {
        val os = System.getProperty("os.name").lowercase()
        try {
            when {
                os.contains("mac") -> {
                    ProcessBuilder("open", "x-apple.systempreferences:com.apple.preference.security?Privacy_LocationServices")
                        .start()
                }
                os.contains("win") -> {
                    ProcessBuilder("cmd", "/c", "start", "ms-settings:privacy-location")
                        .start()
                }
                os.contains("linux") -> {
                    ProcessBuilder("gnome-control-center", "privacy")
                        .start()
                }
            }
        } catch (e: Exception) {
            println("Failed to open system settings: ${e.message}")
        }
    }
}

@Composable
actual fun createPermissionsManager(callback: PermissionCallback): PermissionsManager {
    val viewModel: PermissionsViewModel = koinViewModel()
    return remember(callback) {
        PermissionsManager(callback).apply {
            setViewModel(viewModel)
        }
    }
}
