package com.bitchat.api.dto.mapper

import com.bitchat.api.dto.protocol.BitchatPacket
import com.bitchat.api.dto.protocol.MessagePadding
import com.bitchat.api.dto.util.ByteWriter
import com.bitchat.api.dto.util.CompressionUtil

fun BitchatPacket.toBinaryData(): ByteArray? {
    try {
        var payload = payload
        var originalPayloadSize: UShort? = null
        var isCompressed = false

        if (CompressionUtil.shouldCompress(payload)) {
            CompressionUtil.compress(payload)?.let { compressedPayload ->
                originalPayloadSize = payload.size.toUShort()
                payload = compressedPayload
                isCompressed = true
            }
        }

        val headerSize = getHeaderSize(version)
        val recipientBytes = if (recipientID != null) RECIPIENT_ID_SIZE else 0
        val signatureBytes = if (signature != null) SIGNATURE_SIZE else 0
        val payloadBytes = payload.size + if (isCompressed) 2 else 0
        val capacity = headerSize + SENDER_ID_SIZE + recipientBytes + payloadBytes + signatureBytes + 16 // small slack
        val buffer = ByteWriter(capacity.coerceAtLeast(512))

        buffer.put(version.toByte())
        buffer.put(type.toByte())
        buffer.put(ttl.toByte())

        buffer.putLong(timestamp.toLong())

        var flags: UByte = 0u
        if (recipientID != null) {
            flags = flags or Flags.HAS_RECIPIENT
        }
        if (signature != null) {
            flags = flags or Flags.HAS_SIGNATURE
        }
        if (isCompressed) {
            flags = flags or Flags.IS_COMPRESSED
        }
        buffer.put(flags.toByte())

        val payloadDataSize = payload.size + if (isCompressed) 2 else 0
        if (version >= 2u.toUByte()) {
            buffer.putInt(payloadDataSize)  // 4 bytes for v2+
        } else {
            buffer.putShort(payloadDataSize.toShort())  // 2 bytes for v1
        }

        val senderBytes = senderID.take(SENDER_ID_SIZE).toByteArray()
        buffer.put(senderBytes)
        if (senderBytes.size < SENDER_ID_SIZE) {
            buffer.put(ByteArray(SENDER_ID_SIZE - senderBytes.size))
        }

        // RecipientID (if present)
        recipientID?.let { recipientID ->
            val recipientBytes = recipientID.take(RECIPIENT_ID_SIZE).toByteArray()
            buffer.put(recipientBytes)
            if (recipientBytes.size < RECIPIENT_ID_SIZE) {
                buffer.put(ByteArray(RECIPIENT_ID_SIZE - recipientBytes.size))
            }
        }

        if (isCompressed) {
            val originalSize = originalPayloadSize
            if (originalSize != null) {
                buffer.putShort(originalSize.toShort())
            }
        }
        buffer.put(payload)

        signature?.let { signature ->
            buffer.put(signature.take(SIGNATURE_SIZE).toByteArray())
        }

        val result = buffer.toByteArray()

        val optimalSize = MessagePadding.optimalBlockSize(result.size)
        val paddedData = MessagePadding.pad(result, optimalSize)

        return paddedData

    } catch (e: Exception) {
        // Log.e("BinaryProtocol", "Error encoding packet type ${packet.type}: ${e.message}")
        return null
    }
}
