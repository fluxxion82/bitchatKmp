package com.bitchat.api.dto.mapper

import com.bitchat.domain.chat.model.BitchatFilePacket

internal enum class FilePacketTLVType(val v: UByte) {
    FILE_NAME(0x01u),
    FILE_SIZE(0x02u),
    MIME_TYPE(0x03u),
    CONTENT(0x04u);

    companion object {
        fun from(value: UByte) = entries.find { it.v == value }
    }
}

/**
 * Encode BitchatFilePacket to binary TLV format for wire transmission.
 *
 * Wire format uses standard TLV structure:
 * - Type: 1 byte
 * - Length: 2 bytes (big-endian)
 * - Value: N bytes
 *
 * Content is chunked if larger than 65535 bytes to fit 2-byte length field.
 *
 * @return Encoded bytes, or null if encoding fails
 */
fun BitchatFilePacket.toWireFormat(): ByteArray? {
    try {
        val buffer = mutableListOf<Byte>()

        // FILE_NAME TLV
        val fileNameBytes = fileName.encodeToByteArray()
        if (fileNameBytes.size > 0xFFFF) return null
        buffer.add(FilePacketTLVType.FILE_NAME.v.toByte())
        buffer.addAll(writeUInt16BE(fileNameBytes.size).toList())
        buffer.addAll(fileNameBytes.toList())

        // FILE_SIZE TLV (8 bytes, big-endian)
        buffer.add(FilePacketTLVType.FILE_SIZE.v.toByte())
        buffer.addAll(writeUInt16BE(8).toList())
        buffer.addAll(writeUInt64BE(fileSize.toULong()).toList())

        // MIME_TYPE TLV
        val mimeBytes = mimeType.encodeToByteArray()
        if (mimeBytes.size > 0xFFFF) return null
        buffer.add(FilePacketTLVType.MIME_TYPE.v.toByte())
        buffer.addAll(writeUInt16BE(mimeBytes.size).toList())
        buffer.addAll(mimeBytes.toList())

        // CONTENT TLV(s) - chunked if needed to fit 2-byte length
        val maxChunkSize = 65535
        var offset = 0
        while (offset < content.size) {
            val remaining = content.size - offset
            val chunkSize = minOf(remaining, maxChunkSize)
            buffer.add(FilePacketTLVType.CONTENT.v.toByte())
            buffer.addAll(writeUInt16BE(chunkSize).toList())
            for (i in 0 until chunkSize) {
                buffer.add(content[offset + i])
            }
            offset += chunkSize
        }

        return buffer.toByteArray()
    } catch (e: Exception) {
        return null
    }
}

/**
 * Decode binary TLV format to BitchatFilePacket.
 *
 * @param data Binary TLV-encoded data
 * @return Decoded packet, or null if decoding fails
 */
fun ByteArray.toBitchatFilePacket(): BitchatFilePacket? {
    if (isEmpty()) return null

    var fileName: String? = null
    var fileSize: Long? = null
    var mimeType: String? = null
    val contentChunks = mutableListOf<ByteArray>()

    var offset = 0
    while (offset < size) {
        if (offset + 3 > size) break

        val type = FilePacketTLVType.from(this[offset].toUByte())
        val length = readUInt16BE(this, offset + 1)
        offset += 3

        if (offset + length > size) break

        val value = copyOfRange(offset, offset + length)
        offset += length

        when (type) {
            FilePacketTLVType.FILE_NAME -> fileName = value.decodeToString()
            FilePacketTLVType.FILE_SIZE -> {
                if (value.size >= 8) {
                    fileSize = readUInt64BE(value, 0).toLong()
                }
            }
            FilePacketTLVType.MIME_TYPE -> mimeType = value.decodeToString()
            FilePacketTLVType.CONTENT -> contentChunks.add(value)
            null -> { /* skip unknown TLV types */ }
        }
    }

    val totalContentSize = contentChunks.sumOf { it.size }
    val content = ByteArray(totalContentSize)
    var contentOffset = 0
    for (chunk in contentChunks) {
        chunk.copyInto(content, contentOffset)
        contentOffset += chunk.size
    }

    return if (fileName != null && fileSize != null && mimeType != null) {
        BitchatFilePacket(fileName, fileSize, mimeType, content)
    } else {
        null
    }
}

private fun writeUInt16BE(value: Int): ByteArray {
    return byteArrayOf(
        ((value shr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte()
    )
}

private fun writeUInt64BE(value: ULong): ByteArray {
    return ByteArray(8) { i ->
        ((value shr ((7 - i) * 8)) and 0xFFu).toByte()
    }
}

private fun readUInt16BE(data: ByteArray, offset: Int): Int {
    return ((data[offset].toInt() and 0xFF) shl 8) or
            (data[offset + 1].toInt() and 0xFF)
}

private fun readUInt64BE(data: ByteArray, offset: Int): ULong {
    var result: ULong = 0u
    for (i in 0 until 8) {
        result = (result shl 8) or (data[offset + i].toUByte().toULong())
    }
    return result
}
