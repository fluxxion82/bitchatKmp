package com.bitchat.design.location

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Stub MapPickerLauncher for embedded Linux.
 * Map/geohash picker is not supported on this platform.
 */
@Composable
actual fun rememberMapPickerLauncher(): MapPickerLauncher {
    return remember {
        object : MapPickerLauncher {
            override fun open(initialGeohash: String?, onResult: (String) -> Unit) {
                println("MapPickerLauncher linuxArm64: Map picker not supported on embedded platform")
                // No-op - map picker not available on embedded
            }
        }
    }
}
