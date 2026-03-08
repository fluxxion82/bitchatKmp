package com.bitchat.repo.utils

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.refTo
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.fwrite

/**
 * Linux implementation of file utilities using POSIX APIs.
 */

@OptIn(ExperimentalForeignApi::class)
actual inline fun getFileBytes(path: String): ByteArray? {
    val file = fopen(path, "rb") ?: return null

    return try {
        // Get file size
        fseek(file, 0, SEEK_END)
        val size = ftell(file).toInt()
        fseek(file, 0, SEEK_SET)

        if (size <= 0) return null

        // Read file contents
        val buffer = ByteArray(size)
        val bytesRead = fread(buffer.refTo(0), 1u, size.toULong(), file)
        if (bytesRead.toInt() != size) {
            null
        } else {
            buffer
        }
    } finally {
        fclose(file)
    }
}

@OptIn(ExperimentalForeignApi::class)
actual inline fun saveFile(path: String, fileName: String, fileBytes: ByteArray): String {
    val fullPath = if (path.endsWith("/")) "$path$fileName" else "$path/$fileName"
    val file = fopen(fullPath, "wb")
        ?: throw Exception("Failed to create file: $fullPath")

    try {
        val bytesWritten = fwrite(fileBytes.refTo(0), 1u, fileBytes.size.toULong(), file)
        if (bytesWritten.toInt() != fileBytes.size) {
            throw Exception("Failed to write complete file: $fullPath")
        }
    } finally {
        fclose(file)
    }

    return fullPath
}
