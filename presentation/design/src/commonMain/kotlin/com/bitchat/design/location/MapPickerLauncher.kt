package com.bitchat.design.location

import androidx.compose.runtime.Composable

interface MapPickerLauncher {
    fun open(initialGeohash: String?, onResult: (String) -> Unit)
}

@Composable
expect fun rememberMapPickerLauncher(): MapPickerLauncher