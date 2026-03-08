package com.bitchat.design.imagepicker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.bitchat.mediautils.model.MediaData
import kotlinx.coroutines.CoroutineScope

@Composable
actual fun rememberImagePickerLauncher(
    selectionMode: SelectionMode,
    scope: CoroutineScope,
    onResult: (List<MediaData>) -> Unit,
): ImagePickerLauncher {
    return remember { ImagePickerLauncher(selectionMode) { /* no-op */ } }
}

/**
 * Stub ImagePickerLauncher for embedded Linux.
 * Image picker functionality is not supported on this platform.
 */
actual class ImagePickerLauncher actual constructor(
    private val selectionMode: SelectionMode,
    private val onLaunch: () -> Unit,
) {
    actual fun launch() {
        println("ImagePickerLauncher linuxArm64: Image picker not supported on embedded platform")
        // No-op - image picker not available on embedded
    }
}
