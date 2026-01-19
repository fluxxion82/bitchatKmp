package com.bitchat.api.dto.util

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

actual object Zip {
    actual fun compressRawOrNull(data: ByteArray, minBytes: Int): ByteArray? {
        if (data.size < minBytes) return null
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, /*nowrap=*/true)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream(data.size)
        val buf = ByteArray(1024)
        while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
        deflater.end()
        val compressed = out.toByteArray()
        return compressed.takeIf { it.isNotEmpty() && it.size < data.size }
    }

    actual fun decompressRawThenZlibOrNull(data: ByteArray, originalSizeHint: Int): ByteArray? {
        fun tryInflate(nowrap: Boolean): ByteArray? {
            val inf = Inflater(nowrap)
            return try {
                inf.setInput(data)
                val out = ByteArray(originalSizeHint)
                val actual = inf.inflate(out)
                when {
                    actual == originalSizeHint -> out
                    actual > 0 -> out.copyOf(actual)
                    else -> null
                }
            } catch (_: Throwable) {
                null
            } finally {
                inf.end()
            }
        }
        return tryInflate(nowrap = true) ?: tryInflate(nowrap = false)
    }
}

