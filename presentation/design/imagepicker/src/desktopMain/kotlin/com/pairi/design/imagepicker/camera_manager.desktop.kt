package com.pairi.design.imagepicker

import androidx.compose.runtime.Composable
import com.pairi.mediautils.model.MediaData

@Composable
actual fun rememberCameraManager(onResult: (MediaData?) -> Unit): CameraManager {
    TODO("Not yet implemented")
}

actual class CameraManager actual constructor(onLaunch: () -> Unit) {
    actual fun launch() {
    }
}
