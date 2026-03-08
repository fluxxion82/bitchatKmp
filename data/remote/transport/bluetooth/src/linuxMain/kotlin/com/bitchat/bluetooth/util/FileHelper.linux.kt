package com.bitchat.bluetooth.util

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.stat

/**
 * Linux implementation of file operations using POSIX APIs.
 */

@OptIn(ExperimentalForeignApi::class)
actual suspend fun loadFileBytes(path: String): ByteArray = withContext(Dispatchers.Default) {
    val file = fopen(path, "rb")
        ?: throw Exception("Failed to read file at path: $path")

    try {
        // Get file size
        fseek(file, 0, SEEK_END)
        val size = ftell(file).toInt()
        fseek(file, 0, SEEK_SET)

        if (size <= 0) {
            throw Exception("File is empty or failed to get size: $path")
        }

        // Read file contents
        val buffer = ByteArray(size)
        val bytesRead = fread(buffer.refTo(0), 1u, size.toULong(), file)
        if (bytesRead.toInt() != size) {
            throw Exception("Failed to read complete file: $path")
        }

        buffer
    } finally {
        fclose(file)
    }
}

actual fun getFileName(path: String): String {
    return path.substringAfterLast('/')
}

actual fun getMimeType(path: String): String {
    val extension = path.substringAfterLast('.', "").lowercase()

    return when (extension) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "svg" -> "image/svg+xml"
        "heic" -> "image/heic"

        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "ogg" -> "audio/ogg"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "flac" -> "audio/flac"

        "mp4" -> "video/mp4"
        "mov" -> "video/quicktime"
        "avi" -> "video/x-msvideo"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"

        "pdf" -> "application/pdf"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "txt" -> "text/plain"
        "csv" -> "text/csv"

        "zip" -> "application/zip"
        "rar" -> "application/x-rar-compressed"
        "7z" -> "application/x-7z-compressed"
        "tar" -> "application/x-tar"
        "gz" -> "application/gzip"

        else -> "application/octet-stream"
    }
}

@OptIn(ExperimentalForeignApi::class)
actual suspend fun getFileSize(path: String): Long = withContext(Dispatchers.Default) {
    memScoped {
        val statBuf = alloc<stat>()
        if (stat(path, statBuf.ptr) != 0) {
            throw Exception("Failed to get file attributes for: $path")
        }
        statBuf.st_size
    }
}
