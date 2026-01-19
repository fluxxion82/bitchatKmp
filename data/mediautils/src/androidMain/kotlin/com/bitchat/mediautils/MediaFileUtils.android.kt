package com.bitchat.mediautils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

private var appContext: Context? = null

/**
 * Initialize the media file utils with the application context.
 * This should be called from the Application class or main activity.
 */
fun initMediaFileUtils(context: Context) {
    appContext = context.applicationContext
}

/**
 * Convert a media URL/URI to a local file path.
 * On Android, this copies content:// URIs to local files.
 */
actual suspend fun resolveMediaToLocalPath(mediaUrl: String): String? = withContext(Dispatchers.IO) {
    val context = appContext ?: return@withContext null

    // If it's already a file path, return as-is
    if (mediaUrl.startsWith("/") && !mediaUrl.startsWith("/content")) {
        return@withContext mediaUrl
    }

    // If it's a content:// URI, copy to local file
    if (mediaUrl.startsWith("content://")) {
        return@withContext copyContentToLocalFile(context, mediaUrl)
    }

    // For other cases, return as-is
    mediaUrl
}

/**
 * Read file bytes from a local file path.
 */
actual suspend fun readFileBytes(path: String): ByteArray? = withContext(Dispatchers.IO) {
    try {
        val file = File(path)
        if (file.exists() && file.isFile) {
            file.readBytes()
        } else {
            println("MediaFileUtils: File does not exist or is not a file: $path")
            null
        }
    } catch (e: Exception) {
        println("MediaFileUtils: Error reading file: ${e.message}")
        e.printStackTrace()
        null
    }
}

/**
 * Get the MIME type for a file based on its path/extension.
 */
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
        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
    }
}

/**
 * Save file bytes to local storage in a specified subdirectory.
 */
actual suspend fun saveFileToLocal(bytes: ByteArray, fileName: String, subDir: String): String? = withContext(Dispatchers.IO) {
    val context = appContext ?: return@withContext null
    try {
        val outDir = File(context.filesDir, subDir)
        if (!outDir.exists()) {
            outDir.mkdirs()
        }

        val outputFile = File(outDir, fileName)
        FileOutputStream(outputFile).use { output ->
            output.write(bytes)
        }

        // Verify the file was saved correctly
        if (!outputFile.exists()) {
            println("MediaFileUtils: ERROR - File does not exist after write: ${outputFile.absolutePath}")
            return@withContext null
        }

        val savedSize = outputFile.length()
        if (savedSize != bytes.size.toLong()) {
            println("MediaFileUtils: WARNING - File size mismatch: expected ${bytes.size}, got $savedSize")
        }

        println("MediaFileUtils: Saved file to ${outputFile.absolutePath} ($savedSize bytes, canRead=${outputFile.canRead()})")
        outputFile.absolutePath
    } catch (e: Exception) {
        println("MediaFileUtils: Error saving file: ${e.message}")
        e.printStackTrace()
        null
    }
}

/**
 * Compress an image file for BLE transfer.
 * Progressively reduces quality and size until under maxSizeBytes.
 */
actual suspend fun compressImageForTransfer(path: String, maxSizeBytes: Int): PreparedImageForTransfer? = withContext(Dispatchers.IO) {
    try {
        val file = File(path)
        if (!file.exists()) {
            println("MediaFileUtils: Image file not found: $path")
            return@withContext null
        }

        val originalMime = getMimeType(path)
        val originalFileName = getFileName(path)

        // First, check if original is already small enough
        val originalBytes = file.readBytes()
        if (originalBytes.size <= maxSizeBytes) {
            println("MediaFileUtils: Image already under size limit (${originalBytes.size} bytes)")
            val detectedFormat = detectImageFormat(originalBytes)
            val normalizedName = normalizeImageFileName(originalFileName, detectedFormat)
            val normalizedMime = when (detectedFormat) {
                ImageFormat.PNG -> "image/png"
                ImageFormat.JPEG -> "image/jpeg"
                ImageFormat.WEBP -> "image/webp"
                ImageFormat.GIF -> "image/gif"
                ImageFormat.UNKNOWN -> originalMime
            }
            return@withContext PreparedImageForTransfer(
                bytes = originalBytes,
                mimeType = normalizedMime,
                fileName = normalizedName
            )
        }

        println("MediaFileUtils: Compressing image from ${originalBytes.size} bytes to under $maxSizeBytes bytes")

        // Decode the image
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(path, options)

        val originalWidth = options.outWidth
        val originalHeight = options.outHeight
        println("MediaFileUtils: Original dimensions: ${originalWidth}x${originalHeight}")

        // Calculate sample size to get approximate target size
        var sampleSize = 1
        var targetWidth = originalWidth
        var targetHeight = originalHeight

        // Start with downsampling to reduce memory and size
        while (targetWidth > 800 || targetHeight > 800) {
            sampleSize *= 2
            targetWidth = originalWidth / sampleSize
            targetHeight = originalHeight / sampleSize
        }

        // Decode with sample size
        options.inJustDecodeBounds = false
        options.inSampleSize = sampleSize
        var bitmap = BitmapFactory.decodeFile(path, options) ?: return@withContext null

        println("MediaFileUtils: Decoded with sampleSize=$sampleSize, dimensions: ${bitmap.width}x${bitmap.height}")

        // Try progressively lower quality levels
        val qualities = listOf(85, 70, 55, 40, 25, 15)
        for (quality in qualities) {
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            val compressed = outputStream.toByteArray()

            println("MediaFileUtils: Quality $quality: ${compressed.size} bytes")

            if (compressed.size <= maxSizeBytes) {
                println("MediaFileUtils: Successfully compressed to ${compressed.size} bytes at quality $quality")
                val targetName = normalizeImageFileName(originalFileName, ImageFormat.JPEG)
                return@withContext PreparedImageForTransfer(
                    bytes = compressed,
                    mimeType = "image/jpeg",
                    fileName = targetName
                )
            }
        }

        // If still too large, try scaling down more aggressively
        val scaledWidth = bitmap.width / 2
        val scaledHeight = bitmap.height / 2
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
        bitmap.recycle()
        bitmap = scaledBitmap

        println("MediaFileUtils: Scaled to ${scaledWidth}x${scaledHeight}, trying again")

        for (quality in qualities) {
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            val compressed = outputStream.toByteArray()

            println("MediaFileUtils: Scaled quality $quality: ${compressed.size} bytes")

            if (compressed.size <= maxSizeBytes) {
                println("MediaFileUtils: Successfully compressed scaled image to ${compressed.size} bytes")
                bitmap.recycle()
                val targetName = normalizeImageFileName(originalFileName, ImageFormat.JPEG)
                return@withContext PreparedImageForTransfer(
                    bytes = compressed,
                    mimeType = "image/jpeg",
                    fileName = targetName
                )
            }
        }

        bitmap.recycle()
        println("MediaFileUtils: Could not compress image to under $maxSizeBytes bytes")
        null
    } catch (e: Exception) {
        println("MediaFileUtils: Error compressing image: ${e.message}")
        e.printStackTrace()
        null
    }
}
