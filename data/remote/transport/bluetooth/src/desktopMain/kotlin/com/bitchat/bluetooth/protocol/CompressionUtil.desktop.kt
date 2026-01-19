package com.bitchat.bluetooth.protocol

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

actual fun compressPlatform(data: ByteArray): ByteArray? {
    return try {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
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
        val inflater = Inflater(true)
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
    } catch (e: Exception) {
        null
    }
}
