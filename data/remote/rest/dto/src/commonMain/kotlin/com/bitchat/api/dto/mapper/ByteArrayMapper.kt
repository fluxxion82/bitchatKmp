package com.bitchat.api.dto.mapper

import com.bitchat.api.dto.protocol.BitchatPacket
import com.bitchat.api.dto.protocol.MessagePadding
import com.bitchat.api.dto.util.ByteReader
import com.bitchat.api.dto.util.CompressionUtil

const val HEADER_SIZE_V1 = 13
const val HEADER_SIZE_V2 = 15
const val SENDER_ID_SIZE = 8
const val RECIPIENT_ID_SIZE = 8
const val SIGNATURE_SIZE = 64

object Flags {
    const val HAS_RECIPIENT: UByte = 0x01u
    const val HAS_SIGNATURE: UByte = 0x02u
    const val IS_COMPRESSED: UByte = 0x04u
}

fun getHeaderSize(version: UByte): Int {
    return when (version) {
        1u.toUByte() -> HEADER_SIZE_V1
        else -> HEADER_SIZE_V2  // v2+ will use 4-byte payload length
    }
}

fun ByteArray.toBitchatPacket(): BitchatPacket? {
    // Try decode as-is first (robust when padding wasn't applied) - iOS fix
    decodeCore(this)?.let { return it }

    // If that fails, try after removing padding
    val unpadded = MessagePadding.unpad(this)
    if (unpadded.contentEquals(this)) return null // No padding was removed, already failed

    return decodeCore(unpadded)
}

private fun decodeCore(raw: ByteArray): BitchatPacket? {
    try {
        if (raw.size < HEADER_SIZE_V1 + SENDER_ID_SIZE) return null

        val buffer = ByteReader(raw)

        // Header
        val version = buffer.get().toUByte()
        if (version.toUInt() != 1u && version.toUInt() != 2u) return null  // Support v1 and v2

        val headerSize = getHeaderSize(version)

        val type = buffer.get().toUByte()
        val ttl = buffer.get().toUByte()

        // Timestamp
        val timestamp = buffer.getLong().toULong()

        // Flags
        val flags = buffer.get().toUByte()
        val hasRecipient = (flags and Flags.HAS_RECIPIENT) != 0u.toUByte()
        val hasSignature = (flags and Flags.HAS_SIGNATURE) != 0u.toUByte()
        val isCompressed = (flags and Flags.IS_COMPRESSED) != 0u.toUByte()

        // Payload length - version-dependent (2 or 4 bytes)
        val payloadLength = if (version >= 2u.toUByte()) {
            buffer.getInt().toUInt()  // 4 bytes for v2+
        } else {
            buffer.getShort().toUShort().toUInt()  // 2 bytes for v1, convert to UInt
        }

        // Calculate expected total size
        var expectedSize = headerSize + SENDER_ID_SIZE + payloadLength.toInt()
        if (hasRecipient) expectedSize += RECIPIENT_ID_SIZE
        if (hasSignature) expectedSize += SIGNATURE_SIZE

        if (raw.size < expectedSize) return null

        // SenderID
        val senderID = ByteArray(SENDER_ID_SIZE)
        buffer.get(senderID)

        // RecipientID
        val recipientID = if (hasRecipient) {
            val recipientBytes = ByteArray(RECIPIENT_ID_SIZE)
            buffer.get(recipientBytes)
            recipientBytes
        } else null

        // Payload
        val payload = if (isCompressed) {
            // First 2 bytes are original size
            if (payloadLength.toInt() < 2) return null
            val originalSize = buffer.getShort().toInt()

            // Compressed payload
            val compressedPayload = ByteArray(payloadLength.toInt() - 2)
            buffer.get(compressedPayload)

            // Decompress
            CompressionUtil.decompress(compressedPayload, originalSize) ?: return null
        } else {
            val payloadBytes = ByteArray(payloadLength.toInt())
            buffer.get(payloadBytes)
            payloadBytes
        }

        // Signature
        val signature = if (hasSignature) {
            val signatureBytes = ByteArray(SIGNATURE_SIZE)
            buffer.get(signatureBytes)
            signatureBytes
        } else null

        return BitchatPacket(
            version = version,
            type = type,
            senderID = senderID,
            recipientID = recipientID,
            timestamp = timestamp,
            payload = payload,
            signature = signature,
            ttl = ttl
        )

    } catch (e: Exception) {
        // Log.e("BinaryProtocol", "Error decoding packet: ${e.message}")
        return null
    }
}
