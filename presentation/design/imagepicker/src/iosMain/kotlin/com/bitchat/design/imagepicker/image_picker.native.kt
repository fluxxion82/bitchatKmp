package com.bitchat.design.imagepicker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.bitchat.mediautils.loadMediaData
import com.bitchat.mediautils.model.MediaData
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.autoreleasepool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import platform.Foundation.NSDate
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerConfigurationSelectionOrdered
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIApplication
import platform.darwin.NSObject

@OptIn(BetaInteropApi::class)
@Composable
actual fun rememberImagePickerLauncher(
    selectionMode: SelectionMode,
    scope: CoroutineScope,
    onResult: (List<MediaData>) -> Unit,
): ImagePickerLauncher {
    val delegate =
        object : NSObject(), PHPickerViewControllerDelegateProtocol {
            override fun picker(
                picker: PHPickerViewController,
                didFinishPicking: List<*>,
            ) {
                val results = didFinishPicking as List<PHPickerResult>
                scope.launch {
                    try {
                        val mediaDataList = results.map { result ->
                            async {
                                val isVideo = result.itemProvider.hasItemConformingToTypeIdentifier("public.movie")
                                loadMediaData(result, isVideo)?.let { mediaData ->
                                    val fileName = if (isVideo) {
                                        "video_${NSDate().timeIntervalSince1970}.mov"
                                    } else {
                                        "image_${NSDate().timeIntervalSince1970}.jpg"
                                    }

                                    val fileManager = NSFileManager.defaultManager()
                                    val urls = fileManager.URLsForDirectory(NSDocumentDirectory, NSUserDomainMask) as List<NSURL>
                                    val documentsDirectory = urls[0]

                                    val fileUrl = documentsDirectory.URLByAppendingPathComponent(fileName)
                                    mediaData.writeToFile(fileUrl!!.path!!, true)

                                    autoreleasepool {
                                        mediaData.writeToFile(fileUrl.path!!, true)
                                    }

                                    MediaData(fileUrl.path.orEmpty(), isVideo)
                                }
                            }
                        }.awaitAll().filterNotNull()
                        onResult(mediaDataList)
                        picker.dismissViewControllerAnimated(flag = true) {
                            picker.delegate = null
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }

    return remember {
        ImagePickerLauncher(
            selectionMode = selectionMode,
            onLaunch = {
                val pickerController = createPHPickerViewController(delegate, selectionMode)
                UIApplication.sharedApplication.keyWindow?.rootViewController?.presentViewController(
                    pickerController,
                    true,
                    null,
                )
            },
        )
    }
}

private fun createPHPickerViewController(
    delegate: PHPickerViewControllerDelegateProtocol,
    selection: SelectionMode,
): PHPickerViewController {
    val pickerViewController =
        PHPickerViewController(
            configuration =
            when (selection) {
                is SelectionMode.Multiple ->
                    PHPickerConfiguration().apply {
                        setSelectionLimit(selectionLimit = selection.maxSelection.toLong())
                        setSelection(selection = PHPickerConfigurationSelectionOrdered)
                    }
                SelectionMode.Single ->
                    PHPickerConfiguration().apply {
                        setSelectionLimit(selectionLimit = 1)
                        setSelection(selection = PHPickerConfigurationSelectionOrdered)
                    }
            },
        )
    pickerViewController.delegate = delegate
    return pickerViewController
}

actual class ImagePickerLauncher actual constructor(
    selectionMode: SelectionMode,
    private val onLaunch: () -> Unit,
) {
    actual fun launch() {
        onLaunch()
    }
}
