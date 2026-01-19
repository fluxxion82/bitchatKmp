package com.bitchat.bluetooth.protocol

/**
 * iOS implementation of compression using native compression APIs
 * TODO: Implement using platform.compression APIs when available
 */
actual fun compressPlatform(data: ByteArray): ByteArray? {
    // TODO: Implement iOS compression using platform.compression
    // For now, return null (compression disabled on iOS until implemented)
    return null
}

actual fun decompressPlatform(compressedData: ByteArray, originalSize: Int): ByteArray? {
    // TODO: Implement iOS decompression using platform.compression
    // For now, return null (decompression disabled on iOS until implemented)
    return null
}
