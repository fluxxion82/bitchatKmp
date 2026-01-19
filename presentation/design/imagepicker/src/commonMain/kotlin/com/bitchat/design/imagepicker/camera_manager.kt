package com.bitchat.design.imagepicker

import androidx.compose.runtime.Composable
import com.bitchat.mediautils.model.MediaData

@Composable
expect fun rememberCameraManager(onResult: (MediaData?) -> Unit): CameraManager

expect class CameraManager(
    onLaunch: () -> Unit
) {
    fun launch()
}
