package com.bitchat.screens.permissions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import com.bitchat.design.permissions.PermissionExplanationContent
import com.bitchat.screens.Routes
import com.bitchat.viewmodel.navigation.PermissionError
import com.bitchat.viewmodel.permissions.PermissionsViewModel
import com.bitchat.viewvo.permissions.PermissionStatus
import com.bitchat.viewvo.permissions.PermissionType
import kotlinx.coroutines.flow.receiveAsFlow

@Composable
fun PermissionsScreen(
    navController: NavController,
    viewModel: PermissionsViewModel,
) {
    LaunchedEffect(Unit) {
        viewModel.navigation.receiveAsFlow().collect { event ->
            when (event) {
                is PermissionError -> navController.navigate(Routes.PermissionsError.create(event.deniedPermissions)) {
                    popUpTo(navController.graph.findStartDestination().id)
                }
            }
        }
    }

    var shouldRequestPermissions by remember { mutableStateOf(false) }
    val permissionsManager = createPermissionsManager(object : PermissionCallback {
        override fun onPermissionStatus(permissionType: PermissionType, status: PermissionStatus) {
            println("onPermissionStatus: $permissionType, status: $status")
            viewModel.updatePermissionStatus(permissionType, status)
        }
    })

    val allPermissions = remember(viewModel.requiredPermissions, viewModel.optionalPermissions) {
        (viewModel.requiredPermissions + viewModel.optionalPermissions).toList()
    }

    if (shouldRequestPermissions) {
        permissionsManager.askPermissions(allPermissions)
    }

    PermissionExplanationContent(
        modifier = Modifier,
        permissionTypes = viewModel.requiredPermissions + viewModel.optionalPermissions,
        onContinue = { shouldRequestPermissions = true }
    )
}
