package com.bitchat.noise.model

enum class NoisePayloadType(val value: UByte) {
    PRIVATE_MESSAGE(0x01u),     // Private chat message with TLV encoding
    READ_RECEIPT(0x02u),        // Message was read
    DELIVERED(0x03u),           // Message was delivered
    FILE_TRANSFER(0x20u);


    companion object {
        fun fromValue(value: UByte): NoisePayloadType? {
            return entries.find { it.value == value }
        }
    }
}

data class NoisePayload(
    val type: NoisePayloadType,
    val data: ByteArray
) {
    fun encode(): ByteArray {
        val result = ByteArray(1 + data.size)
        result[0] = type.value.toByte()
        data.copyInto(result, destinationOffset = 1)
        return result
    }

    companion object {
        fun decode(data: ByteArray): NoisePayload? {
            if (data.isEmpty()) return null

            val typeValue = data[0].toUByte()
            val type = NoisePayloadType.fromValue(typeValue) ?: return null

            val payloadData = if (data.size > 1) {
                data.copyOfRange(1, data.size)
            } else {
                ByteArray(0)
            }

            return NoisePayload(type, payloadData)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true

        other as NoisePayload

        if (type != other.type) return false
        if (!data.contentEquals(other.data)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}

data class PrivateMessagePacket(
    val messageID: String,
    val content: String
) {
    private enum class TLVType(val value: UByte) {
        MESSAGE_ID(0x00u),
        CONTENT(0x01u);

        companion object {
            fun fromValue(value: UByte): TLVType? {
                return entries.find { it.value == value }
            }
        }
    }

    fun encode(): ByteArray? {
        val messageIDData = messageID.encodeToByteArray()
        val contentData = content.encodeToByteArray()

        // Check size limits (TLV length field is 1 byte = max 255)
        if (messageIDData.size > 255 || contentData.size > 255) {
            return null
        }

        // Calculate total size
        val tlvMessageIDSize = 1 + 1 + messageIDData.size  // type + length + data
        val tlvContentSize = 1 + 1 + contentData.size      // type + length + data
        val totalSize = tlvMessageIDSize + tlvContentSize

        val result = ByteArray(totalSize)
        var offset = 0

        // TLV for messageID
        result[offset++] = TLVType.MESSAGE_ID.value.toByte()
        result[offset++] = messageIDData.size.toByte()
        messageIDData.copyInto(result, offset)
        offset += messageIDData.size

        // TLV for content
        result[offset++] = TLVType.CONTENT.value.toByte()
        result[offset++] = contentData.size.toByte()
        contentData.copyInto(result, offset)

        return result
    }

    companion object {
        fun decode(data: ByteArray): PrivateMessagePacket? {
            var offset = 0
            var messageID: String? = null
            var content: String? = null

            while (offset + 2 <= data.size) {
                // Read TLV type
                val typeValue = data[offset].toUByte()
                val type = TLVType.fromValue(typeValue) ?: return null
                offset += 1

                // Read TLV length
                val length = data[offset].toUByte().toInt()
                offset += 1

                // Check bounds
                if (offset + length > data.size) return null

                // Read TLV value
                val value = data.copyOfRange(offset, offset + length)
                offset += length

                when (type) {
                    TLVType.MESSAGE_ID -> {
                        messageID = value.decodeToString()
                    }

                    TLVType.CONTENT -> {
                        content = value.decodeToString()
                    }
                }
            }

            return if (messageID != null && content != null) {
                PrivateMessagePacket(messageID, content)
            } else {
                null
            }
        }
    }

    override fun toString(): String {
        return "PrivateMessagePacket(messageID='$messageID', content='${content.take(50)}${if (content.length > 50) "..." else ""}')"
    }
}
