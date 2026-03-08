package com.bitchat.api.dto.util

/**
 * Linux stub implementation of Zip compression.
 *
 * Zlib is not available via cinterop on linuxArm64 without additional setup.
 * This stub provides no-op implementations that return null (no compression).
 *
 * TODO: Add zlib cinterop for linuxArm64 or use a pure-Kotlin compression library
 */
actual object Zip {
    actual fun compressRawOrNull(data: ByteArray, minBytes: Int): ByteArray? {
        // Compression not available on Linux ARM64 yet
        return null
    }

    actual fun decompressRawThenZlibOrNull(data: ByteArray, originalSizeHint: Int): ByteArray? {
        // Decompression not available on Linux ARM64 yet
        // Return null - callers should handle uncompressed data
        return null
    }
}
