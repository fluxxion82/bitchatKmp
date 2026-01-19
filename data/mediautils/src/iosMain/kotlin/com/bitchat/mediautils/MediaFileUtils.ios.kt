package com.bitchat.mediautils

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithBytes
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.posix.memcpy

actual suspend fun resolveMediaToLocalPath(mediaUrl: String): String? {
    // On iOS, the media picker returns file:// URLs or local paths
    // Just return as-is since the file is already local
    return if (mediaUrl.startsWith("file://")) {
        mediaUrl.removePrefix("file://")
    } else {
        mediaUrl
    }
}

@OptIn(ExperimentalForeignApi::class)
actual suspend fun readFileBytes(path: String): ByteArray? = withContext(Dispatchers.IO) {
    try {
        val data = NSData.dataWithContentsOfFile(path) ?: return@withContext null
        val bytes = ByteArray(data.length.toInt())
        bytes.usePinned { pinned ->
            memcpy(pinned.addressOf(0), data.bytes, data.length)
        }
        bytes
    } catch (e: Exception) {
        println("MediaFileUtils iOS: Error reading file: ${e.message}")
        null
    }
}

actual fun getMimeType(path: String): String {
    val extension = path.substringAfterLast('.', "").lowercase()
    return when (extension) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "m4a" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "ogg" -> "audio/ogg"
        "aac" -> "audio/aac"
        else -> "application/octet-stream"
    }
}

@OptIn(ExperimentalForeignApi::class)
actual suspend fun saveFileToLocal(bytes: ByteArray, fileName: String, subDir: String): String? = withContext(Dispatchers.IO) {
    try {
        val fileManager = NSFileManager.defaultManager
        val documentsUrl = fileManager.URLsForDirectory(NSDocumentDirectory, NSUserDomainMask).firstOrNull() as? NSURL
            ?: return@withContext null

        val subDirUrl = documentsUrl.URLByAppendingPathComponent(subDir)
            ?: return@withContext null

        // Create directory if needed
        if (!fileManager.fileExistsAtPath(subDirUrl.path ?: "")) {
            fileManager.createDirectoryAtURL(subDirUrl, true, null, null)
        }

        val fileUrl = subDirUrl.URLByAppendingPathComponent(fileName)
            ?: return@withContext null

        val filePath = fileUrl.path ?: return@withContext null

        // Write ByteArray directly
        val success = bytes.usePinned { pinned ->
            val data = NSData.dataWithBytes(pinned.addressOf(0), bytes.size.toULong())
            data?.writeToFile(filePath, true) ?: false
        }

        // Return result
        if (success) {
            // Verify the file was saved correctly
            if (!fileManager.fileExistsAtPath(filePath)) {
                println("MediaFileUtils iOS: ERROR - File does not exist after write: $filePath")
                return@withContext null
            }

            val attributes = fileManager.attributesOfItemAtPath(filePath, null)
            val savedSize = (attributes?.get("NSFileSize") as? Number)?.toLong() ?: -1

            println("MediaFileUtils iOS: Saved file to $filePath ($savedSize bytes, expected ${bytes.size})")
            filePath
        } else {
            println("MediaFileUtils iOS: Failed to write file to $filePath")
            null
        }
    } catch (e: Exception) {
        println("MediaFileUtils iOS: Error saving file: ${e.message}")
        null
    }
}

@OptIn(ExperimentalForeignApi::class)
actual suspend fun compressImageForTransfer(path: String, maxSizeBytes: Int): PreparedImageForTransfer? = withContext(Dispatchers.IO) {
    try {
        // Read original file
        val originalData = NSData.dataWithContentsOfFile(path) ?: return@withContext null
        val originalSize = originalData.length.toInt()
        val originalMime = getMimeType(path)
        val originalFileName = getFileName(path)

        if (originalSize <= maxSizeBytes) {
            println("MediaFileUtils iOS: Image already under size limit ($originalSize bytes)")
            val bytes = ByteArray(originalSize)
            bytes.usePinned { pinned ->
                memcpy(pinned.addressOf(0), originalData.bytes, originalData.length)
            }
            val detectedFormat = detectImageFormat(bytes)
            val normalizedName = normalizeImageFileName(originalFileName, detectedFormat)
            val normalizedMime = when (detectedFormat) {
                ImageFormat.PNG -> "image/png"
                ImageFormat.JPEG -> "image/jpeg"
                ImageFormat.WEBP -> "image/webp"
                ImageFormat.GIF -> "image/gif"
                ImageFormat.UNKNOWN -> originalMime
            }
            return@withContext PreparedImageForTransfer(
                bytes = bytes,
                mimeType = normalizedMime,
                fileName = normalizedName
            )
        }

        // Load image using UIImage
        var currentImage = UIImage.imageWithData(originalData) ?: run {
            println("MediaFileUtils iOS: Failed to load image")
            return@withContext null
        }

        val originalWidth = currentImage.size.useContents { width }
        val originalHeight = currentImage.size.useContents { height }
        println("MediaFileUtils iOS: Compressing image from $originalSize bytes (${originalWidth.toInt()}x${originalHeight.toInt()}) to target $maxSizeBytes bytes")

        // Quality levels to try
        val qualities = listOf(0.8, 0.6, 0.4, 0.25, 0.15, 0.1)
        var bestData: NSData? = null

        // Try compression at current size first
        for (quality in qualities) {
            val compressed = UIImageJPEGRepresentation(currentImage, quality)
            if (compressed != null) {
                val compressedSize = compressed.length.toInt()
                println("MediaFileUtils iOS: Quality $quality: $compressedSize bytes")
                if (compressedSize <= maxSizeBytes) {
                    bestData = compressed
                    break
                }
            }
        }

        // If still too large, scale down progressively
        if (bestData == null) {
            var scaleFactor = 0.75
            var scaleAttempts = 0
            val maxScaleAttempts = 5

            while (bestData == null && scaleAttempts < maxScaleAttempts) {
                val newWidth = (originalWidth * scaleFactor).toInt()
                val newHeight = (originalHeight * scaleFactor).toInt()
                println("MediaFileUtils iOS: Scaling to ${newWidth}x${newHeight} (scale: $scaleFactor)")

                // Scale the image
                val scaledImage = scaleImage(currentImage, newWidth, newHeight)
                if (scaledImage == null) {
                    println("MediaFileUtils iOS: Failed to scale image")
                    scaleFactor *= 0.5
                    scaleAttempts++
                    continue
                }

                // Try compression at each quality level
                for (quality in qualities) {
                    val compressed = UIImageJPEGRepresentation(scaledImage, quality)
                    if (compressed != null) {
                        val compressedSize = compressed.length.toInt()
                        println("MediaFileUtils iOS: Scaled quality $quality: $compressedSize bytes")
                        if (compressedSize <= maxSizeBytes) {
                            bestData = compressed
                            break
                        }
                    }
                }

                scaleFactor *= 0.5
                scaleAttempts++
            }
        }

        // Convert NSData to ByteArray
        val resultData = bestData ?: run {
            println("MediaFileUtils iOS: Could not compress image to target size after scaling")
            return@withContext null
        }

        val resultSize = resultData.length.toInt()
        println("MediaFileUtils iOS: Compressed image from $originalSize to $resultSize bytes")
        val bytes = ByteArray(resultSize)
        bytes.usePinned { pinned ->
            memcpy(pinned.addressOf(0), resultData.bytes, resultData.length)
        }
        val targetName = normalizeImageFileName(originalFileName, ImageFormat.JPEG)
        PreparedImageForTransfer(
            bytes = bytes,
            mimeType = "image/jpeg",
            fileName = targetName
        )
    } catch (e: Exception) {
        println("MediaFileUtils iOS: Error compressing image: ${e.message}")
        null
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun scaleImage(image: UIImage, targetWidth: Int, targetHeight: Int): UIImage? {
    val size = CGSizeMake(targetWidth.toDouble(), targetHeight.toDouble())
    UIGraphicsBeginImageContextWithOptions(size, false, 1.0)
    image.drawInRect(CGRectMake(0.0, 0.0, targetWidth.toDouble(), targetHeight.toDouble()))
    val scaledImage = UIGraphicsGetImageFromCurrentImageContext()
    UIGraphicsEndImageContext()
    return scaledImage
}
