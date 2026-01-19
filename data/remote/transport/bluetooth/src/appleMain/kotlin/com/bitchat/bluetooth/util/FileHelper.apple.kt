package com.bitchat.bluetooth.util

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy

/**
 * iOS implementation of file operations
 */

@OptIn(ExperimentalForeignApi::class)
actual suspend fun loadFileBytes(path: String): ByteArray = withContext(Dispatchers.Default) {
    val data = NSData.dataWithContentsOfFile(path)
        ?: throw Exception("Failed to read file at path: $path")

    ByteArray(data.length.toInt()).apply {
        usePinned {
            memcpy(it.addressOf(0), data.bytes, data.length)
        }
    }
}

actual fun getFileName(path: String): String {
    val nsUrl = NSURL.fileURLWithPath(path)
    return nsUrl.lastPathComponent ?: path.substringAfterLast('/')
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

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
actual suspend fun getFileSize(path: String): Long = withContext(Dispatchers.Default) {
    val fileManager = NSFileManager.defaultManager
    val attributes = fileManager.attributesOfItemAtPath(path, null)
        ?: throw Exception("Failed to get file attributes for: $path")

    (attributes[NSFileSize] as? NSNumber)?.longValue
        ?: throw Exception("Failed to get file size for: $path")
}
