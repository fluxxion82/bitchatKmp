package com.bitchat.api.dto.util

const val COMPRESSION_THRESHOLD_BYTES: Int = 100

object CompressionUtil {
    fun shouldCompress(data: ByteArray): Boolean {
        // Don't compress if:
        // 1. Data is too small
        // 2. Data appears to be already compressed (high entropy)
        if (data.size < COMPRESSION_THRESHOLD_BYTES) return false

        // Simple entropy check - count unique bytes (exact same as iOS)
        val byteFrequency = mutableMapOf<Byte, Int>()
        for (byte in data) {
            byteFrequency[byte] = (byteFrequency[byte] ?: 0) + 1
        }

        val uniqueByteRatio = byteFrequency.size.toDouble() / minOf(data.size, 256).toDouble()
        return uniqueByteRatio < 0.9 // Compress if less than 90% unique bytes
    }

    /**
     * Compress data using deflate algorithm - exact same as iOS
     * iOS COMPRESSION_ZLIB actually produces raw deflate data (no zlib headers)
     */
    fun compress(data: ByteArray): ByteArray? {
        // Skip compression for small data
        if (data.size < COMPRESSION_THRESHOLD_BYTES) return null

        return Zip.compressRawOrNull(data, COMPRESSION_THRESHOLD_BYTES)
    }

    /**
     * Decompress deflate compressed data - exact same as iOS
     * iOS COMPRESSION_ZLIB produces raw deflate data (no headers)
     * This function tries raw deflate first, then falls back to zlib format
     */
    fun decompress(compressedData: ByteArray, originalSize: Int): ByteArray? {
        return Zip.decompressRawThenZlibOrNull(compressedData, originalSize)
    }

    /**
     * Test function to verify deflate compression works correctly
     * This can be called during app initialization to ensure compatibility
     */
    fun testCompression(): Boolean {
        try {
            // Create test data that should compress well (repeating pattern like iOS would use)
            val testMessage = "This is a test message that should compress well. ".repeat(10)
            val originalData = testMessage.encodeToByteArray()

            // Log.d("CompressionUtil", "Testing deflate compression with ${originalData.size} bytes")

            // Test shouldCompress
            val shouldCompress = shouldCompress(originalData)
            // Log.d("CompressionUtil", "shouldCompress() returned: $shouldCompress")

            if (!shouldCompress) {
                // Log.e("CompressionUtil", "shouldCompress failed for test data")
                return false
            }

            // Test compression
            val compressed = compress(originalData)
            if (compressed == null) {
                // Log.e("CompressionUtil", "Compression failed")
                return false
            }

            // Log.d("CompressionUtil", "Compressed ${originalData.size} bytes to ${compressed.size} bytes (${(compressed.size.toDouble() / originalData.size * 100).toInt()}%)")

            // Test decompression
            val decompressed = decompress(compressed, originalData.size)
            if (decompressed == null) {
                // Log.e("CompressionUtil", "Decompression failed")
                return false
            }

            // Verify data integrity
            val isIdentical = originalData.contentEquals(decompressed)
            // Log.d("CompressionUtil", "Data integrity check: $isIdentical")

            if (!isIdentical) {
                // Log.e("CompressionUtil", "Decompressed data doesn't match original")
                return false
            }

            // Log.i("CompressionUtil", "✅ deflate compression test PASSED - ready for iOS compatibility")
            return true

        } catch (e: Exception) {
            // Log.e("CompressionUtil", "deflate compression test failed: ${e.message}")
            return false
        }
    }
}

expect object Zip {
    // raw deflate first, fallback to zlib headers when decompressing
    fun compressRawOrNull(data: ByteArray, minBytes: Int = 0): ByteArray?
    fun decompressRawThenZlibOrNull(data: ByteArray, originalSizeHint: Int): ByteArray?
}
