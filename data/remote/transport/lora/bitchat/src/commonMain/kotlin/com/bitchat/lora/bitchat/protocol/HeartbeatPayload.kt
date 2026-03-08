package com.bitchat.lora.bitchat.protocol

/**
 * Heartbeat payload for LoRa peer discovery.
 *
 * Heartbeats are broadcast periodically (every 60 seconds) to announce
 * presence on the LoRa network. Other devices track these heartbeats
 * to maintain a list of active peers.
 *
 * Payload format:
 * ```
 * ┌──────────┬─────────────┬──────────────┐
 * │ type     │ deviceId    │ nickname     │
 * │ 1 byte   │ 8 bytes     │ variable     │
 * │ 0x01     │ unique ID   │ UTF-8 string │
 * └──────────┴─────────────┴──────────────┘
 * ```
 *
 * Total size: 9 + nickname.length bytes (max ~223 for nickname to stay within LoRa limits)
 */
data class HeartbeatPayload(
    /**
     * Unique device identifier (8 bytes, represented as hex string).
     *
     * This should be stable across app restarts but unique per device.
     * Typically derived from a UUID or device-specific identifier.
     */
    val deviceId: String,

    /**
     * User's display nickname.
     *
     * Limited to MAX_NICKNAME_LENGTH bytes when encoded as UTF-8.
     */
    val nickname: String
) {
    init {
        require(deviceId.length == DEVICE_ID_LENGTH) {
            "Device ID must be exactly $DEVICE_ID_LENGTH characters (got ${deviceId.length})"
        }
        require(nickname.encodeToByteArray().size <= MAX_NICKNAME_LENGTH) {
            "Nickname exceeds maximum length of $MAX_NICKNAME_LENGTH bytes"
        }
    }

    /**
     * Serialize this heartbeat to a ByteArray for transmission.
     *
     * Format: [TYPE_HEARTBEAT (1)] [deviceId (8)] [nickname (variable)]
     */
    fun toBytes(): ByteArray {
        val nicknameBytes = nickname.encodeToByteArray()
        val result = ByteArray(1 + DEVICE_ID_BYTES + nicknameBytes.size)

        // Type byte
        result[0] = TYPE_HEARTBEAT

        // Device ID (8 bytes from 16-char hex string)
        val deviceIdBytes = hexStringToBytes(deviceId)
        deviceIdBytes.copyInto(result, 1)

        // Nickname
        nicknameBytes.copyInto(result, 1 + DEVICE_ID_BYTES)

        return result
    }

    companion object {
        /** Packet type identifier for heartbeats */
        const val TYPE_HEARTBEAT: Byte = 0x01

        /** Device ID string length (16 hex characters = 8 bytes) */
        const val DEVICE_ID_LENGTH = 16

        /** Device ID byte length when serialized */
        const val DEVICE_ID_BYTES = 8

        /** Maximum nickname length in bytes (UTF-8 encoded) */
        const val MAX_NICKNAME_LENGTH = 200

        /**
         * Minimum payload size: type (1) + deviceId (8) + empty nickname (0)
         */
        const val MIN_SIZE = 1 + DEVICE_ID_BYTES

        /**
         * Parse a heartbeat payload from raw bytes.
         *
         * @param data Raw bytes from LoRa frame payload
         * @return Parsed HeartbeatPayload or null if invalid
         */
        fun fromBytes(data: ByteArray): HeartbeatPayload? {
            if (data.size < MIN_SIZE) {
                return null
            }

            // Check type
            if (data[0] != TYPE_HEARTBEAT) {
                return null
            }

            // Extract device ID (bytes 1-8)
            val deviceIdBytes = data.copyOfRange(1, 1 + DEVICE_ID_BYTES)
            val deviceId = bytesToHexString(deviceIdBytes)

            // Extract nickname (remaining bytes)
            val nicknameBytes = data.copyOfRange(1 + DEVICE_ID_BYTES, data.size)
            val nickname = try {
                nicknameBytes.decodeToString()
            } catch (e: Exception) {
                return null
            }

            return HeartbeatPayload(deviceId = deviceId, nickname = nickname)
        }

        /**
         * Check if the given payload bytes represent a heartbeat.
         */
        fun isHeartbeat(data: ByteArray): Boolean {
            return data.isNotEmpty() && data[0] == TYPE_HEARTBEAT
        }

        private fun hexStringToBytes(hex: String): ByteArray {
            require(hex.length % 2 == 0) { "Hex string must have even length" }
            return ByteArray(hex.length / 2) { i ->
                val index = i * 2
                ((hex[index].digitToInt(16) shl 4) + hex[index + 1].digitToInt(16)).toByte()
            }
        }

        private fun bytesToHexString(bytes: ByteArray): String {
            return bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        }
    }
}
