package com.pairi.design.imagepicker

import androidx.compose.runtime.Composable
import com.pairi.mediautils.model.MediaData
import kotlinx.coroutines.CoroutineScope

@Composable
actual fun rememberImagePickerLauncher(
    selectionMode: SelectionMode,
    scope: CoroutineScope,
    onResult: (List<MediaData>) -> Unit,
): ImagePickerLauncher {
    TODO("Not yet implemented")
}

actual class ImagePickerLauncher actual constructor(
    selectionMode: SelectionMode,
    onLaunch: () -> Unit
) {
    actual fun launch() {
    }
}
