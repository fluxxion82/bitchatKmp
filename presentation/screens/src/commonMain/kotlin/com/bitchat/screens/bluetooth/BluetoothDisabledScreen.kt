package com.bitchat.screens.bluetooth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavController
import com.bitchat.design.errors.BluetoothDisabledContent

@Composable
fun BluetoothDisabledScreen(navController: NavController) {
    var isLoading by remember { mutableStateOf(false) }
    val bleLauncher = rememberBluetoothEnabler(
        onGranted = {
            isLoading = false
            navController.popBackStack()
        },
        onDenied = {
            isLoading = false
        },
    )

    BluetoothDisabledContent(
        onEnableBluetooth = {
            isLoading = true
            bleLauncher.invoke()
        },
        isLoading = isLoading,
    )
}
