package com.bitchat.bluetooth.protocol

object CompressionUtil {
    private const val COMPRESSION_THRESHOLD = 200  // bytes - same as iOS

    fun shouldCompress(data: ByteArray): Boolean {
        // Don't compress if:
        // 1. Data is too small
        // 2. Data appears to be already compressed (high entropy)
        if (data.size < COMPRESSION_THRESHOLD) return false

        // Simple entropy check - count unique bytes (exact same as iOS)
        val byteFrequency = mutableMapOf<Byte, Int>()
        for (byte in data) {
            byteFrequency[byte] = (byteFrequency[byte] ?: 0) + 1
        }

        // If we have very high byte diversity, data is likely already compressed
        val uniqueByteRatio = byteFrequency.size.toDouble() / minOf(data.size, 256).toDouble()
        return uniqueByteRatio < 0.9 // Compress if less than 90% unique bytes
    }

    fun compress(data: ByteArray): ByteArray? {
        if (data.size < COMPRESSION_THRESHOLD) return null

        return try {
            val compressed = compressPlatform(data)
            if (compressed != null && compressed.isNotEmpty() && compressed.size < data.size) {
                compressed
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun decompress(compressedData: ByteArray, originalSize: Int): ByteArray? {
        return try {
            val decompressed = decompressPlatform(compressedData, originalSize)
            if (decompressed != null && decompressed.size == originalSize) {
                decompressed
            } else if (decompressed != null && decompressed.isNotEmpty()) {
                decompressed
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}

expect fun compressPlatform(data: ByteArray): ByteArray?
expect fun decompressPlatform(compressedData: ByteArray, originalSize: Int): ByteArray?
