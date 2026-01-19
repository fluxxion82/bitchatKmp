package com.bitchat.screens.permissions

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import bitchatkmp.presentation.design.generated.resources.Res
import bitchatkmp.presentation.design.generated.resources.critical_permissions_denied
import bitchatkmp.presentation.design.generated.resources.please_grant_these_permissions
import com.bitchat.design.errors.InitializationErrorContent
import com.bitchat.design.mapper.toDisplayName
import com.bitchat.viewmodel.permissions.PermissionsErrorViewModel
import com.bitchat.viewvo.permissions.PermissionStatus
import com.bitchat.viewvo.permissions.PermissionType
import org.jetbrains.compose.resources.stringResource

@Composable
fun PermissionsErrorScreen(
    viewModel: PermissionsErrorViewModel,
) {
    val message = buildString {
        val top = stringResource(Res.string.critical_permissions_denied)
        appendLine(top)
        appendLine()
        viewModel.deniedPermissions.forEach { permission ->
            appendLine("- ${permission.toDisplayName()}")
        }
        appendLine()
        appendLine(stringResource(Res.string.please_grant_these_permissions))
    }

    val permissionsManager = createPermissionsManager(object : PermissionCallback {
        override fun onPermissionStatus(permissionType: PermissionType, status: PermissionStatus) {}
    })

    var launchSettings by remember { mutableStateOf(false) }
    if (launchSettings) {
        launchSettings = false
        permissionsManager.launchSettings()
    }

    InitializationErrorContent(
        modifier = Modifier.fillMaxSize(),
        errorMessage = message,
        onRetry = {
            launchSettings = false
            viewModel.onRetryClick()
        },
        onOpenSettings = {
            launchSettings = true
        },
    )
}
