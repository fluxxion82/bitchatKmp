package com.bitchat.mediautils

expect suspend fun resolveMediaToLocalPath(mediaUrl: String): String?
expect suspend fun readFileBytes(path: String): ByteArray?
expect fun getMimeType(path: String): String
expect suspend fun saveFileToLocal(bytes: ByteArray, fileName: String, subDir: String): String?

fun getFileName(path: String): String {
    return path.substringAfterLast('/').substringAfterLast('\\')
}

const val MAX_BLE_FILE_SIZE = 100 * 1024  // 100KB

/**
 * Compress an image file if it's too large for BLE transfer.
 * Returns compressed bytes if compression was successful and size is under limit,
 * or null if the file cannot be compressed to fit.
 */
expect suspend fun compressImageForTransfer(
    path: String,
    maxSizeBytes: Int = MAX_BLE_FILE_SIZE
): PreparedImageForTransfer?

data class PreparedImageForTransfer(
    val bytes: ByteArray,
    val mimeType: String,
    val fileName: String
)

enum class ImageFormat {
    PNG, JPEG, WEBP, GIF, UNKNOWN
}

/**
 * Best-effort detection of image format from leading bytes.
 */
fun detectImageFormat(bytes: ByteArray): ImageFormat {
    if (bytes.size >= 8) {
        if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()) {
            return ImageFormat.PNG
        }
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
            return ImageFormat.JPEG
        }
        if (bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte()) {
            // WEBP starts with RIFF....WEBP
            val header = bytes.take(12).toByteArray()
            val headerString = header.decodeToString()
            if (headerString.contains("WEBP")) {
                return ImageFormat.WEBP
            }
        }
        if (bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte()) {
            return ImageFormat.GIF
        }
    }
    return ImageFormat.UNKNOWN
}

fun normalizeImageFileName(originalFileName: String, format: ImageFormat): String {
    val base = originalFileName.substringBeforeLast('.', originalFileName)
    val ext = when (format) {
        ImageFormat.PNG -> ".png"
        ImageFormat.JPEG -> ".jpg"
        ImageFormat.WEBP -> ".webp"
        ImageFormat.GIF -> ".gif"
        ImageFormat.UNKNOWN -> originalFileName.substringAfterLast('.', "").let { existingExt ->
            if (existingExt.isNotBlank()) ".${existingExt}" else ""
        }
    }
    if (ext.isEmpty()) return originalFileName
    return if (originalFileName.lowercase().endsWith(ext.lowercase())) originalFileName else base + ext
}
