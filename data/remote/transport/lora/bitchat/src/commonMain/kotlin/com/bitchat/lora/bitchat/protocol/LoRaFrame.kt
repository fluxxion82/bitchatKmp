package com.bitchat.lora.bitchat.protocol

/**
 * LoRa frame format for transmitting BitchatPackets over LoRa radio.
 *
 * Frame structure (5-byte header + payload):
 * ```
 * ┌───────────┬────────────┬────────────┬───────┬─────────────┐
 * │ messageId │ fragIndex  │ fragTotal  │ flags │ payload     │
 * │ 2 bytes   │ 1 byte     │ 1 byte     │ 1 byte│ ≤232 bytes  │
 * └───────────┴────────────┴────────────┴───────┴─────────────┘
 * ```
 *
 * - Max LoRa packet: 237 bytes (SF9, BW125kHz)
 * - Header: 5 bytes
 * - Max payload per frame: 232 bytes
 */
data class LoRaFrame(
    /** Unique message identifier (wraps at 65535) */
    val messageId: UShort,
    /** Fragment index (0-based) */
    val fragmentIndex: UByte,
    /** Total number of fragments for this message */
    val totalFragments: UByte,
    /** Flags byte for future use (reserved) */
    val flags: UByte = 0u,
    /** Payload data (max 232 bytes) */
    val payload: ByteArray
) {
    init {
        require(payload.size <= MAX_PAYLOAD) {
            "Payload size ${payload.size} exceeds maximum $MAX_PAYLOAD bytes"
        }
        require(fragmentIndex < totalFragments) {
            "Fragment index $fragmentIndex must be less than total fragments $totalFragments"
        }
    }

    /**
     * Serialize this frame to a ByteArray for transmission.
     */
    fun toBytes(): ByteArray {
        val result = ByteArray(HEADER_SIZE + payload.size)

        // Message ID (big-endian)
        result[0] = (messageId.toInt() shr 8).toByte()
        result[1] = messageId.toByte()

        // Fragment index
        result[2] = fragmentIndex.toByte()

        // Total fragments
        result[3] = totalFragments.toByte()

        // Flags
        result[4] = flags.toByte()

        // Payload
        payload.copyInto(result, HEADER_SIZE)

        return result
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as LoRaFrame

        if (messageId != other.messageId) return false
        if (fragmentIndex != other.fragmentIndex) return false
        if (totalFragments != other.totalFragments) return false
        if (flags != other.flags) return false
        if (!payload.contentEquals(other.payload)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = messageId.hashCode()
        result = 31 * result + fragmentIndex.hashCode()
        result = 31 * result + totalFragments.hashCode()
        result = 31 * result + flags.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }

    override fun toString(): String {
        return "LoRaFrame(messageId=$messageId, frag=${fragmentIndex}/${totalFragments}, flags=$flags, payloadSize=${payload.size})"
    }

    companion object {
        /** Header size in bytes */
        const val HEADER_SIZE = 5

        /** Maximum payload size per frame (237 - 5 = 232) */
        const val MAX_PAYLOAD = 232

        /** Maximum total LoRa frame size */
        const val MAX_FRAME_SIZE = 237

        // Flag constants for the flags byte
        /** No special flags */
        const val FLAG_NONE: UByte = 0x00u

        /** Frame contains a heartbeat payload (peer discovery) */
        const val FLAG_HEARTBEAT: UByte = 0x01u

        /** Frame is a direct message (not broadcast) */
        const val FLAG_DIRECT: UByte = 0x02u

        /** Frame requires acknowledgment */
        const val FLAG_ACK_REQUIRED: UByte = 0x04u

        /** Frame is an acknowledgment response */
        const val FLAG_ACK: UByte = 0x08u

        /**
         * Deserialize a LoRaFrame from raw bytes.
         *
         * @param data Raw byte array received from LoRa radio
         * @return Parsed LoRaFrame or null if data is invalid
         */
        fun fromBytes(data: ByteArray): LoRaFrame? {
            if (data.size < HEADER_SIZE) {
                return null
            }

            if (data.size > MAX_FRAME_SIZE) {
                return null
            }

            val messageId = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
            val fragmentIndex = data[2].toUByte()
            val totalFragments = data[3].toUByte()
            val flags = data[4].toUByte()

            // Validate fragment indices
            if (fragmentIndex >= totalFragments || totalFragments == 0.toUByte()) {
                return null
            }

            val payload = data.copyOfRange(HEADER_SIZE, data.size)

            return LoRaFrame(
                messageId = messageId.toUShort(),
                fragmentIndex = fragmentIndex,
                totalFragments = totalFragments,
                flags = flags,
                payload = payload
            )
        }
    }
}
