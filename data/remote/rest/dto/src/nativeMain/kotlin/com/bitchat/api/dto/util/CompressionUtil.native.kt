package com.bitchat.api.dto.util

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import platform.zlib.MAX_WBITS
import platform.zlib.ZLIB_VERSION
import platform.zlib.Z_BUF_ERROR
import platform.zlib.Z_DEFAULT_COMPRESSION
import platform.zlib.Z_DEFAULT_STRATEGY
import platform.zlib.Z_DEFLATED
import platform.zlib.Z_FINISH
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.deflate
import platform.zlib.deflateEnd
import platform.zlib.deflateInit2_
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2_
import platform.zlib.z_stream

@OptIn(ExperimentalForeignApi::class)
actual object Zip {
    private fun deflateZlibRaw(data: ByteArray): ByteArray? = memScoped {
        val strm = alloc<z_stream>().apply {
            zalloc = null; zfree = null; opaque = null
        }
        // raw deflate: windowBits = -MAX_WBITS (-15)
        if (deflateInit2_(
                strm.ptr,
                Z_DEFAULT_COMPRESSION,
                Z_DEFLATED,
                -MAX_WBITS,
                8,
                Z_DEFAULT_STRATEGY,
                ZLIB_VERSION,
                sizeOf<z_stream>().toInt()
            ) != Z_OK
        )
            return null

        try {
            data.usePinned { dataPinned ->
                strm.next_in = dataPinned.addressOf(0).reinterpret()
                strm.avail_in = data.size.toUInt()

                val result = mutableListOf<ByteArray>()

                do {
                    val chunk = ByteArray(1024)
                    chunk.usePinned { chunkPinned ->
                        strm.next_out = chunkPinned.addressOf(0).reinterpret()
                        strm.avail_out = chunk.size.toUInt()
                        val rc = deflate(strm.ptr, if (strm.avail_in == 0u) Z_FINISH else Z_NO_FLUSH)
                        val produced = (chunk.size - strm.avail_out.toInt())
                        if (produced > 0) result += chunk.copyOf(produced)
                        if (rc == Z_STREAM_END) return@memScoped result.fold(ByteArray(0)) { acc, b -> acc + b }.let { compressed ->
                            if (compressed.isNotEmpty() && compressed.size < data.size) compressed else null
                        }
                        if (rc != Z_OK && rc != Z_BUF_ERROR) return@memScoped null
                    }
                } while (true)
            }
            return null
        } finally {
            deflateEnd(strm.ptr)
        }
    }

    private fun inflateZlib(data: ByteArray, windowBits: Int, originalSizeHint: Int): ByteArray? = memScoped {
        val strm = alloc<z_stream>().apply {
            zalloc = null; zfree = null; opaque = null
        }
        if (inflateInit2_(strm.ptr, windowBits, ZLIB_VERSION, sizeOf<z_stream>().toInt()) != Z_OK) return null
        try {
            data.usePinned { dataPinned ->
                strm.next_in = dataPinned.addressOf(0).reinterpret()
                strm.avail_in = data.size.toUInt()

                val pieces = mutableListOf<ByteArray>()
                do {
                    val chunk = ByteArray(1024)
                    chunk.usePinned { chunkPinned ->
                        strm.next_out = chunkPinned.addressOf(0).reinterpret()
                        strm.avail_out = chunk.size.toUInt()
                        val rc = inflate(strm.ptr, Z_NO_FLUSH)
                        val produced = chunk.size - strm.avail_out.toInt()
                        if (produced > 0) pieces += chunk.copyOf(produced)
                        if (rc == Z_STREAM_END) return@memScoped pieces.fold(ByteArray(0)) { acc, b -> acc + b }
                        if (rc != Z_OK && rc != Z_BUF_ERROR) return@memScoped null
                    }
                } while (true)
            }
            return null
        } finally {
            inflateEnd(strm.ptr)
        }
    }

    actual fun compressRawOrNull(data: ByteArray, minBytes: Int): ByteArray? {
        if (data.size < minBytes) return null
        return deflateZlibRaw(data)
    }

    actual fun decompressRawThenZlibOrNull(data: ByteArray, originalSizeHint: Int): ByteArray? {
        return inflateZlib(data, -MAX_WBITS, originalSizeHint) ?: inflateZlib(data, MAX_WBITS, originalSizeHint)
    }
}
