package com.bitchat.mediautils

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.fwrite
import platform.posix.getenv
import platform.posix.mkdir

/**
 * Linux ARM64 (embedded) implementation of media file utilities.
 * Provides basic file operations for the embedded platform.
 */

@OptIn(ExperimentalForeignApi::class)
actual suspend fun resolveMediaToLocalPath(mediaUrl: String): String? {
    return if (mediaUrl.startsWith("file://")) {
        mediaUrl.removePrefix("file://")
    } else {
        mediaUrl
    }
}

@OptIn(ExperimentalForeignApi::class)
actual suspend fun readFileBytes(path: String): ByteArray? = withContext(Dispatchers.IO) {
    try {
        val file = fopen(path, "rb") ?: return@withContext null

        // Get file size
        fseek(file, 0, SEEK_END)
        val size = ftell(file).toInt()
        fseek(file, 0, SEEK_SET)

        if (size <= 0) {
            fclose(file)
            return@withContext null
        }

        val bytes = ByteArray(size)
        bytes.usePinned { pinned ->
            fread(pinned.addressOf(0), 1u.convert(), size.convert(), file)
        }
        fclose(file)
        bytes
    } catch (e: Exception) {
        println("MediaFileUtils linuxArm64: Error reading file: ${e.message}")
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
        // Use /home/bitchat or /tmp for embedded
        val homeDir = getenv("HOME")?.toString() ?: "/tmp"
        val appDir = "$homeDir/.bitchat"
        val outDir = "$appDir/$subDir"

        // Create directories if needed
        mkdir(appDir, 0x1FF.convert()) // 0777
        mkdir(outDir, 0x1FF.convert())

        val outputPath = "$outDir/$fileName"
        val file = fopen(outputPath, "wb") ?: return@withContext null

        bytes.usePinned { pinned ->
            fwrite(pinned.addressOf(0), 1u.convert(), bytes.size.convert(), file)
        }
        fclose(file)

        println("MediaFileUtils linuxArm64: Saved file to $outputPath")
        outputPath
    } catch (e: Exception) {
        println("MediaFileUtils linuxArm64: Error saving file: ${e.message}")
        null
    }
}

/**
 * Embedded platform: Image compression is not supported.
 * Returns null to indicate compression failed.
 * The caller should handle this by not sending images that exceed size limits.
 */
actual suspend fun compressImageForTransfer(path: String, maxSizeBytes: Int): PreparedImageForTransfer? = withContext(Dispatchers.IO) {
    try {
        val bytes = readFileBytes(path) ?: return@withContext null
        val mime = getMimeType(path)
        val fileName = getFileName(path)

        // If already under size limit, return as-is
        if (bytes.size <= maxSizeBytes) {
            val detectedFormat = detectImageFormat(bytes)
            val normalizedName = normalizeImageFileName(fileName, detectedFormat)
            val normalizedMime = when (detectedFormat) {
                ImageFormat.PNG -> "image/png"
                ImageFormat.JPEG -> "image/jpeg"
                ImageFormat.WEBP -> "image/webp"
                ImageFormat.GIF -> "image/gif"
                ImageFormat.UNKNOWN -> mime
            }
            return@withContext PreparedImageForTransfer(
                bytes = bytes,
                mimeType = normalizedMime,
                fileName = normalizedName
            )
        }

        // Cannot compress on embedded platform
        println("MediaFileUtils linuxArm64: Image too large (${bytes.size} bytes) and compression not supported")
        null
    } catch (e: Exception) {
        println("MediaFileUtils linuxArm64: Error processing image: ${e.message}")
        null
    }
}
