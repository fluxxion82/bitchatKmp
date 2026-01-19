package com.bitchat.bluetooth.protocol

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

actual fun compressPlatform(data: ByteArray): ByteArray? {
    return try {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true) // true = raw deflate, no headers
        deflater.setInput(data)
        deflater.finish()

        val outputStream = ByteArrayOutputStream(data.size)
        val buffer = ByteArray(1024)

        while (!deflater.finished()) {
            val count = deflater.deflate(buffer)
            outputStream.write(buffer, 0, count)
        }
        deflater.end()

        outputStream.toByteArray()
    } catch (e: Exception) {
        null
    }
}

actual fun decompressPlatform(compressedData: ByteArray, originalSize: Int): ByteArray? {
    return try {
        // iOS COMPRESSION_ZLIB produces raw deflate format (no headers)
        val inflater = Inflater(true) // true = raw deflate, no headers
        inflater.setInput(compressedData)

        val decompressedBuffer = ByteArray(originalSize)
        val actualSize = inflater.inflate(decompressedBuffer)
        inflater.end()

        // Verify decompressed size matches expected
        if (actualSize == originalSize) {
            decompressedBuffer
        } else if (actualSize > 0) {
            // Handle case where actual size is different
            decompressedBuffer.copyOfRange(0, actualSize)
        } else {
            null
        }
    } catch (e: Exception) {
        // Fallback: try with zlib headers in case of mixed usage
        try {
            val inflater = Inflater(false) // false = expect zlib headers
            inflater.setInput(compressedData)

            val decompressedBuffer = ByteArray(originalSize)
            val actualSize = inflater.inflate(decompressedBuffer)
            inflater.end()

            if (actualSize == originalSize) {
                decompressedBuffer
            } else if (actualSize > 0) {
                decompressedBuffer.copyOfRange(0, actualSize)
            } else {
                null
            }
        } catch (fallbackException: Exception) {
            null
        }
    }
}
