package com.bitchat.screens.location

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.bitchat.design.errors.LocationDisabledContent
import com.bitchat.viewmodel.location.LocationDisabledViewModel

@Composable
fun LocationDisabledScreen(
    viewModel: LocationDisabledViewModel,
) {
    var isLoading by remember { mutableStateOf(false) }
    val locationSettingsLauncher = rememberLocationSettingsLauncher {
        isLoading = false
        viewModel.onRetryClick()
    }

    LocationDisabledContent(
        onEnableLocation = {
            isLoading = true
            locationSettingsLauncher()
        },
        onRetry = {
            isLoading = false
            viewModel.onRetryClick()
        },
        isLoading = isLoading,
    )
}
