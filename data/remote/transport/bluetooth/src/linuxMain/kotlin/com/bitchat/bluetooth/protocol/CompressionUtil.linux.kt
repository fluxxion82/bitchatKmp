package com.bitchat.bluetooth.protocol

/**
 * Linux implementation of compression.
 * Currently stubbed - compression disabled until zlib bindings added.
 */
actual fun compressPlatform(data: ByteArray): ByteArray? {
    // Compression not implemented for Linux yet
    // Could add zlib cinterop in the future
    return null
}

actual fun decompressPlatform(compressedData: ByteArray, originalSize: Int): ByteArray? {
    // Decompression not implemented for Linux yet
    return null
}
