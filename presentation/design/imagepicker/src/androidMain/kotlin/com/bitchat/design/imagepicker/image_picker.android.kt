package com.bitchat.design.imagepicker

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Build
import android.os.ext.SdkExtensions
import android.provider.MediaStore
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.bitchat.design.imagepicker.SelectionMode.Companion.INFINITY
import com.bitchat.mediautils.model.MediaData
import kotlinx.coroutines.CoroutineScope

@Composable
actual fun rememberImagePickerLauncher(
    selectionMode: SelectionMode,
    scope: CoroutineScope,
    onResult: (List<MediaData>) -> Unit,
): ImagePickerLauncher {
    return when (selectionMode) {
        SelectionMode.Single ->
            pickSingleImage(
                selectionMode = selectionMode,
                onResult = onResult,
            )

        is SelectionMode.Multiple ->
            pickMultipleImages(
                selectionMode = selectionMode,
                onResult = onResult,
            )
    }
}

@Composable
private fun pickSingleImage(
    selectionMode: SelectionMode,
    onResult: (List<MediaData>) -> Unit,
): ImagePickerLauncher {
    val context = LocalContext.current
    var imagePickerLauncher: ImagePickerLauncher? = null
    val singleImagePickerLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.PickVisualMedia(),
            onResult = { uri ->
                val mediaList = uri?.let {
                    val contentResolver = context.contentResolver
                    val mimeType = contentResolver.getType(uri)
                    val isVideo = mimeType?.startsWith("video/") == true
                    listOf(
                        MediaData(uri.toString(), isVideo),
                    )
                } ?: emptyList()

                onResult(mediaList)
                imagePickerLauncher?.markPhotoPickerInactive()
            },
        )

    val legacyImagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri ->
            uri?.let {
                val contentResolver = context.contentResolver
                val mimeType = contentResolver.getType(uri)
                val isVideo = mimeType?.startsWith("video/") == true
                onResult(listOf(MediaData(uri.toString(), isVideo)))
            }
            imagePickerLauncher?.markPhotoPickerInactive()
        }
    )

    imagePickerLauncher =
        remember {
            ImagePickerLauncher(
                selectionMode = selectionMode,
                onLaunch = {
                    try {
                        singleImagePickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                        )
                    } catch (e: ActivityNotFoundException) {
//                        Toast.makeText(
//                            context,
//                            "No gallery app available.",
//                            Toast.LENGTH_LONG
//                        ).show()

                        legacyImagePickerLauncher.launch("*/*")
                    }
                },
            )
        }

    return imagePickerLauncher
}

@Composable
private fun pickMultipleImages(
    selectionMode: SelectionMode.Multiple,
    onResult: (List<MediaData>) -> Unit,
): ImagePickerLauncher {
    val context = LocalContext.current
    var imagePickerLauncher: ImagePickerLauncher? = null
    val maxSelection =
        if (selectionMode.maxSelection == INFINITY) {
            getMaxItems()
        } else {
            selectionMode.maxSelection
        }

    val multipleImagePickerLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetMultipleContents(),
            onResult = { uriList ->
                onResult(
                    uriList.map { uri ->
                        val contentResolver = context.contentResolver
                        val mimeType = contentResolver.getType(uri)
                        val isVideo = mimeType?.startsWith("video/") == true
                        MediaData(uri.path.orEmpty(), isVideo)
                    }
                )
                imagePickerLauncher?.markPhotoPickerInactive()
            },
        )

    imagePickerLauncher =
        remember {
            ImagePickerLauncher(
                selectionMode = selectionMode,
                onLaunch = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        multipleImagePickerLauncher.launch("image/*")
                    } else {
                        (multipleImagePickerLauncher as ManagedActivityResultLauncher<PickVisualMediaRequest, List<@JvmSuppressWildcards Uri>>).launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                        )
                    }
                },
            )
        }

    return imagePickerLauncher
}

internal fun isSystemPickerAvailable(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        true
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        SdkExtensions.getExtensionVersion(Build.VERSION_CODES.R) >= 2
    } else {
        false
    }
}

@SuppressLint("NewApi", "ClassVerificationFailure")
internal fun getMaxItems() =
    if (isSystemPickerAvailable()) {
        MediaStore.getPickImagesMaxLimit()
    } else {
        Integer.MAX_VALUE
    }

actual class ImagePickerLauncher actual constructor(
    selectionMode: SelectionMode,
    private val onLaunch: () -> Unit,
) {
    private var isPhotoPickerActive = false

    fun markPhotoPickerInactive() {
        isPhotoPickerActive = false
    }

    actual fun launch() {
        if (isPhotoPickerActive) return

        isPhotoPickerActive = true
        onLaunch()
    }
}
