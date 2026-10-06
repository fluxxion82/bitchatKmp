package com.bitchat.mediautils

import com.bitchat.domain.base.logPath
import com.bitchat.domain.base.logError
import com.bitchat.local.statedir.StateDirectory
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.posix.EINTR
import platform.posix.O_CLOEXEC
import platform.posix.O_CREAT
import platform.posix.O_EXCL
import platform.posix.O_NOFOLLOW
import platform.posix.O_WRONLY
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.close
import platform.posix.errno
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.open
import platform.posix.unlink
import platform.posix.write

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
        println("MediaFileUtils linuxArm64: Error reading file: ${logError(e)}")
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

/**
 * Saves a received file as `~/.bitchat/<subDir>/<fileName>` and returns that path, or null when it was not
 * saved.
 *
 * [fileName] is used only when it is one plain path component: anything else could name a place outside
 * [subDir]. The directories on the way are this user's own private ones ([StateDirectory]), and the file
 * is created here or not written at all: an existing one is not replaced and a link is not followed.
 */
@OptIn(ExperimentalForeignApi::class)
actual suspend fun saveFileToLocal(bytes: ByteArray, fileName: String, subDir: String): String? = withContext(Dispatchers.IO) {
    if (!isPlainFileName(fileName)) {
        println("MediaFileUtils linuxArm64: not saving a file whose name is not a plain file name: ${logPath(fileName)}")
        return@withContext null
    }
    try {
        val outDir = StateDirectory.own(*subDir.split('/').toTypedArray())
        val outputPath = "$outDir/$fileName"
        // O_EXCL: created here or not written at all. A file that is already there may be one a message
        // shows, and it is not replaced (nor is a link in its place followed).
        val descriptor = open(outputPath, O_WRONLY or O_CREAT or O_EXCL or O_NOFOLLOW or O_CLOEXEC, MODE_0600)
        if (descriptor < 0) {
            println("MediaFileUtils linuxArm64: Could not create ${logPath(outputPath)}")
            return@withContext null
        }
        val written = writeAll(descriptor, bytes)
        // Closed exactly once, whatever the write did: a second close could hit a descriptor another
        // thread has opened in between.
        val closed = close(descriptor) == 0
        if (!written || !closed) {
            unlink(outputPath)
            println("MediaFileUtils linuxArm64: Could not write ${logPath(outputPath)}")
            return@withContext null
        }

        println("MediaFileUtils linuxArm64: Saved file to ${logPath(outputPath)}")
        outputPath
    } catch (e: Exception) {
        println("MediaFileUtils linuxArm64: Error saving file: ${logError(e)}")
        null
    }
}

/** Writes all of [bytes] to [descriptor]; false when the file could not take them. */
@OptIn(ExperimentalForeignApi::class)
private fun writeAll(descriptor: Int, bytes: ByteArray): Boolean {
    if (bytes.isEmpty()) return true
    return bytes.usePinned { pinned ->
        var offset = 0
        while (offset < bytes.size) {
            val count = write(descriptor, pinned.addressOf(offset), (bytes.size - offset).convert()).toLong()
            if (count < 0 && errno == EINTR) continue
            if (count <= 0) return@usePinned false
            offset += count.toInt()
        }
        true
    }
}

private const val MODE_0600 = 0x180

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
        println("MediaFileUtils linuxArm64: Error processing image: ${logError(e)}")
        null
    }
}
