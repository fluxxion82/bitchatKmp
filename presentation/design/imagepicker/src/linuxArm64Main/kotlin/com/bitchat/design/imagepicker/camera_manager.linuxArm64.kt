package com.bitchat.design.imagepicker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.bitchat.mediautils.model.MediaData

@Composable
actual fun rememberCameraManager(onResult: (MediaData?) -> Unit): CameraManager {
    return remember { CameraManager { /* no-op */ } }
}

/**
 * Stub CameraManager for embedded Linux.
 * Camera functionality is not supported on this platform.
 */
actual class CameraManager actual constructor(
    private val onLaunch: () -> Unit
) {
    actual fun launch() {
        println("CameraManager linuxArm64: Camera not supported on embedded platform")
        // No-op - camera not available on embedded
    }
}
