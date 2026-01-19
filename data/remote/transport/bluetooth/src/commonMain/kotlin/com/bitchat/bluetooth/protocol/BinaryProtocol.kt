package com.bitchat.bluetooth.protocol

object BinaryProtocol {
    // Header layout: version(1) + type(1) + ttl(1) + timestamp(8) + flags(1) + payloadLen(2 or 4)
    private const val HEADER_SIZE_V1 = 14  // 1+1+1+8+1+2 = 14
    private const val HEADER_SIZE_V2 = 16  // 1+1+1+8+1+4 = 16
    private const val SENDER_ID_SIZE = 8
    private const val RECIPIENT_ID_SIZE = 8
    private const val SIGNATURE_SIZE = 64

    private fun getHeaderSize(version: UByte): Int {
        return when (version) {
            1u.toUByte() -> HEADER_SIZE_V1
            else -> HEADER_SIZE_V2  // v2+ will use 4-byte payload length
        }
    }

    fun encode(packet: BitchatPacket): ByteArray? {
        try {
            // Try to compress payload if beneficial
            var payload = packet.payload
            var originalPayloadSize: UShort? = null
            var isCompressed = false
            // Compression header only carries a 2-byte original size; skip compression if payload is too large
            val canSafelyCompress = payload.size <= UShort.MAX_VALUE.toInt() && CompressionUtil.shouldCompress(payload)
            if (canSafelyCompress) {
                CompressionUtil.compress(payload)?.let { compressedPayload ->
                    originalPayloadSize = payload.size.toUShort()
                    payload = compressedPayload
                    isCompressed = true
                }
            }

            // Calculate total size
            val headerSize = getHeaderSize(packet.version)
            val recipientBytes = if (packet.recipientID != null) RECIPIENT_ID_SIZE else 0
            val signatureBytes = if (packet.signature != null) SIGNATURE_SIZE else 0
            val payloadBytes = payload.size + if (isCompressed) 2 else 0
            val capacity = headerSize + SENDER_ID_SIZE + recipientBytes + payloadBytes + signatureBytes

            val buffer = ByteArray(capacity.coerceAtLeast(512))
            var offset = 0

            // Header
            buffer[offset++] = packet.version.toByte()
            buffer[offset++] = packet.type.toByte()
            buffer[offset++] = packet.ttl.toByte()

            // Timestamp (8 bytes, big-endian)
            val timestamp = packet.timestamp.toLong()
            buffer[offset++] = (timestamp shr 56).toByte()
            buffer[offset++] = (timestamp shr 48).toByte()
            buffer[offset++] = (timestamp shr 40).toByte()
            buffer[offset++] = (timestamp shr 32).toByte()
            buffer[offset++] = (timestamp shr 24).toByte()
            buffer[offset++] = (timestamp shr 16).toByte()
            buffer[offset++] = (timestamp shr 8).toByte()
            buffer[offset++] = timestamp.toByte()

            // Flags
            var flags: UByte = 0u
            if (packet.recipientID != null) {
                flags = flags or Flags.HAS_RECIPIENT
            }
            if (packet.signature != null) {
                flags = flags or Flags.HAS_SIGNATURE
            }
            if (isCompressed) {
                flags = flags or Flags.IS_COMPRESSED
            }
            buffer[offset++] = flags.toByte()

            // Payload length (2 or 4 bytes, big-endian)
            val payloadDataSize = payload.size + if (isCompressed) 2 else 0
            if (packet.version >= 2u.toUByte()) {
                // 4 bytes for v2+
                buffer[offset++] = (payloadDataSize shr 24).toByte()
                buffer[offset++] = (payloadDataSize shr 16).toByte()
                buffer[offset++] = (payloadDataSize shr 8).toByte()
                buffer[offset++] = payloadDataSize.toByte()
            } else {
                // 2 bytes for v1
                buffer[offset++] = (payloadDataSize shr 8).toByte()
                buffer[offset++] = payloadDataSize.toByte()
            }

            // SenderID (exactly 8 bytes)
            val senderBytes = packet.senderID.take(SENDER_ID_SIZE).toByteArray()
            senderBytes.copyInto(buffer, offset)
            offset += SENDER_ID_SIZE

            // RecipientID (if present)
            packet.recipientID?.let { recipientID ->
                val recipBytes = recipientID.take(RECIPIENT_ID_SIZE).toByteArray()
                recipBytes.copyInto(buffer, offset)
                offset += RECIPIENT_ID_SIZE
            }

            // Payload (with original size prepended if compressed)
            if (isCompressed) {
                val originalSize = originalPayloadSize
                if (originalSize != null) {
                    buffer[offset++] = (originalSize.toInt() shr 8).toByte()
                    buffer[offset++] = originalSize.toByte()
                }
            }
            payload.copyInto(buffer, offset)
            offset += payload.size

            // Signature (if present)
            packet.signature?.let { signature ->
                signature.take(SIGNATURE_SIZE).toByteArray().copyInto(buffer, offset)
                offset += SIGNATURE_SIZE
            }

            val result = buffer.copyOfRange(0, offset)

            // Apply padding to standard block sizes for traffic analysis resistance
            val optimalSize = MessagePadding.optimalBlockSize(result.size)
            val paddedData = MessagePadding.pad(result, optimalSize)

            return paddedData

        } catch (e: Exception) {
            logError("BinaryProtocol", "Error encoding packet type ${packet.type}: ${e.message}")
            return null
        }
    }

    fun decode(data: ByteArray): BitchatPacket? {
        // Try decode as-is first (robust when padding wasn't applied)
        decodeCore(data)?.let { return it }

        // If that fails, try after removing padding
        val unpadded = MessagePadding.unpad(data)
        if (unpadded.contentEquals(data)) return null // No padding was removed, already failed

        return decodeCore(unpadded)
    }

    /**
     * Core decoding implementation used by decode() with and without padding removal
     */
    private fun decodeCore(raw: ByteArray): BitchatPacket? {
        try {
            if (raw.size < HEADER_SIZE_V1 + SENDER_ID_SIZE) return null

            var offset = 0

            // Header
            val version = raw[offset++].toUByte()
            if (version.toUInt() != 1u && version.toUInt() != 2u) return null  // Support v1 and v2

            val headerSize = getHeaderSize(version)

            val type = raw[offset++].toUByte()
            val ttl = raw[offset++].toUByte()

            // Timestamp (8 bytes, big-endian)
            val timestamp = (
                    (raw[offset++].toLong() and 0xFF shl 56) or
                            (raw[offset++].toLong() and 0xFF shl 48) or
                            (raw[offset++].toLong() and 0xFF shl 40) or
                            (raw[offset++].toLong() and 0xFF shl 32) or
                            (raw[offset++].toLong() and 0xFF shl 24) or
                            (raw[offset++].toLong() and 0xFF shl 16) or
                            (raw[offset++].toLong() and 0xFF shl 8) or
                            (raw[offset++].toLong() and 0xFF)
                    ).toULong()

            // Flags
            val flags = raw[offset++].toUByte()
            val hasRecipient = (flags and Flags.HAS_RECIPIENT) != 0u.toUByte()
            val hasSignature = (flags and Flags.HAS_SIGNATURE) != 0u.toUByte()
            val isCompressed = (flags and Flags.IS_COMPRESSED) != 0u.toUByte()

            // Payload length - version-dependent (2 or 4 bytes)
            val payloadLength = if (version >= 2u.toUByte()) {
                // 4 bytes for v2+
                (
                        (raw[offset++].toInt() and 0xFF shl 24) or
                                (raw[offset++].toInt() and 0xFF shl 16) or
                                (raw[offset++].toInt() and 0xFF shl 8) or
                                (raw[offset++].toInt() and 0xFF)
                        ).toUInt()
            } else {
                // 2 bytes for v1
                (
                        (raw[offset++].toInt() and 0xFF shl 8) or
                                (raw[offset++].toInt() and 0xFF)
                        ).toUShort().toUInt()
            }

            // Calculate expected total size
            var expectedSize = headerSize + SENDER_ID_SIZE + payloadLength.toInt()
            if (hasRecipient) expectedSize += RECIPIENT_ID_SIZE
            if (hasSignature) expectedSize += SIGNATURE_SIZE

            if (raw.size < expectedSize) return null

            // SenderID
            val senderID = raw.copyOfRange(offset, offset + SENDER_ID_SIZE)
            offset += SENDER_ID_SIZE

            // RecipientID
            val recipientID = if (hasRecipient) {
                val recipBytes = raw.copyOfRange(offset, offset + RECIPIENT_ID_SIZE)
                offset += RECIPIENT_ID_SIZE
                recipBytes
            } else null

            // Payload
            val payload = if (isCompressed) {
                // First 2 bytes are original size
                if (payloadLength.toInt() < 2) return null
                val originalSize = (
                        (raw[offset++].toInt() and 0xFF shl 8) or
                                (raw[offset++].toInt() and 0xFF)
                        )

                // Compressed payload
                val compressedPayload = raw.copyOfRange(offset, offset + payloadLength.toInt() - 2)
                offset += payloadLength.toInt() - 2

                // Decompress
                CompressionUtil.decompress(compressedPayload, originalSize) ?: return null
            } else {
                val payloadBytes = raw.copyOfRange(offset, offset + payloadLength.toInt())
                offset += payloadLength.toInt()
                payloadBytes
            }

            // Signature
            val signature = if (hasSignature) {
                val signatureBytes = raw.copyOfRange(offset, offset + SIGNATURE_SIZE)
                offset += SIGNATURE_SIZE
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
            logError("BinaryProtocol", "Error decoding packet: ${e.message}")
            return null
        }
    }

    object Flags {
        const val HAS_RECIPIENT: UByte = 0x01u
        const val HAS_SIGNATURE: UByte = 0x02u
        const val IS_COMPRESSED: UByte = 0x04u
    }
}

expect fun logError(tag: String, message: String)
expect fun logDebug(tag: String, message: String)
expect fun logInfo(tag: String, message: String)
