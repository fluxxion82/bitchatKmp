package com.bitchat.design.imagepicker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.bitchat.mediautils.model.MediaData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

private val IMAGE_EXTENSIONS = arrayOf("jpg", "jpeg", "png", "gif", "bmp", "webp")
private val VIDEO_EXTENSIONS = arrayOf("mp4", "avi", "mov", "mkv", "webm", "m4v")
private val ALL_MEDIA_EXTENSIONS = IMAGE_EXTENSIONS + VIDEO_EXTENSIONS

@Composable
actual fun rememberImagePickerLauncher(
    selectionMode: SelectionMode,
    scope: CoroutineScope,
    onResult: (List<MediaData>) -> Unit
): ImagePickerLauncher {
    return remember(selectionMode) {
        ImagePickerLauncher(
            selectionMode = selectionMode,
            onLaunch = {
                scope.launch(Dispatchers.IO) {
                    val selectedFiles = showFileChooser(selectionMode)
                    val mediaDataList = selectedFiles.map { file ->
                        val extension = file.extension.lowercase()
                        val isVideo = extension in VIDEO_EXTENSIONS
                        MediaData(file.absolutePath, isVideo)
                    }
                    onResult(mediaDataList)
                }
            }
        )
    }
}

private fun showFileChooser(selectionMode: SelectionMode): List<File> {
    var result: List<File> = emptyList()

    SwingUtilities.invokeAndWait {
        val fileChooser = JFileChooser().apply {
            dialogTitle = "Select Image or Video"
            fileSelectionMode = JFileChooser.FILES_ONLY
            isAcceptAllFileFilterUsed = false

            addChoosableFileFilter(
                FileNameExtensionFilter(
                    "Images & Videos (${ALL_MEDIA_EXTENSIONS.joinToString(", ") { "*.$it" }})",
                    *ALL_MEDIA_EXTENSIONS
                )
            )
            addChoosableFileFilter(
                FileNameExtensionFilter(
                    "Images (${IMAGE_EXTENSIONS.joinToString(", ") { "*.$it" }})",
                    *IMAGE_EXTENSIONS
                )
            )
            addChoosableFileFilter(
                FileNameExtensionFilter(
                    "Videos (${VIDEO_EXTENSIONS.joinToString(", ") { "*.$it" }})",
                    *VIDEO_EXTENSIONS
                )
            )

            isMultiSelectionEnabled = selectionMode is SelectionMode.Multiple
        }

        val returnValue = fileChooser.showOpenDialog(null)

        if (returnValue == JFileChooser.APPROVE_OPTION) {
            result = when (selectionMode) {
                is SelectionMode.Single -> listOfNotNull(fileChooser.selectedFile)
                is SelectionMode.Multiple -> {
                    val files = fileChooser.selectedFiles.toList()
                    if (selectionMode.maxSelection > 0 && files.size > selectionMode.maxSelection) {
                        files.take(selectionMode.maxSelection)
                    } else {
                        files
                    }
                }
            }
        }
    }

    return result
}

actual class ImagePickerLauncher actual constructor(
    private val selectionMode: SelectionMode,
    private val onLaunch: () -> Unit
) {
    private var isPickerActive = false

    actual fun launch() {
        if (isPickerActive) return

        isPickerActive = true
        try {
            onLaunch()
        } finally {
            isPickerActive = false
        }
    }
}
