package com.bitchat.nostr

import com.bitchat.api.dto.mapper.toBinaryData
import com.bitchat.api.dto.protocol.BitchatPacket
import com.bitchat.api.dto.protocol.MESSAGE_TTL_HOPS
import com.bitchat.api.dto.protocol.MessageType
import com.bitchat.noise.model.NoisePayloadType
import com.bitchat.noise.model.PrivateMessagePacket
import io.ktor.utils.io.core.*
import kotlin.io.encoding.Base64.Default.encode
import kotlin.time.Clock

/**
 * BitChat-over-Nostr Adapter
 * Direct port from iOS implementation for 100% compatibility
 */
object NostrEmbeddedBitChat {

    /**
     * Build a `bitchat1:` base64url-encoded BitChat packet carrying a private message for Nostr DMs.
     */
    fun encodePMForNostr(
        content: String,
        messageID: String,
        recipientPeerID: String,
        senderPeerID: String
    ): String? {
        try {
            // TLV-encode the private message
            val pm = PrivateMessagePacket(messageID = messageID, content = content)
            val tlv = pm.encode() ?: return null

            // Prefix with NoisePayloadType
            val payload = ByteArray(1 + tlv.size)
            payload[0] = NoisePayloadType.PRIVATE_MESSAGE.value.toByte()
            // System.arraycopy(tlv, 0, payload, 1, tlv.size)
            tlv.copyInto(
                destination = payload,
                destinationOffset = 1,
                startIndex = 0,
                endIndex = tlv.size
            )

            // Determine 8-byte recipient ID to embed
            val recipientIDHex = normalizeRecipientPeerID(recipientPeerID)

            val packet = BitchatPacket(
                version = 1u,
                type = MessageType.NOISE_ENCRYPTED.value,
                senderID = hexStringToByteArray(senderPeerID),
                recipientID = hexStringToByteArray(recipientIDHex),
                timestamp = Clock.System.now().toEpochMilliseconds().toULong(),
                payload = payload,
                signature = null,
                ttl = MESSAGE_TTL_HOPS
            )

            val data = packet.toBinaryData() ?: return null
            return "bitchat1:" + base64URLEncode(data)
        } catch (e: Exception) {
            // Log.e(TAG, "Failed to encode PM for Nostr: ${e.message}")
            return null
        }
    }

    /**
     * Build a `bitchat1:` base64url-encoded BitChat packet carrying a delivery/read ack for Nostr DMs.
     */
    fun encodeAckForNostr(
        type: NoisePayloadType,
        messageID: String,
        recipientPeerID: String,
        senderPeerID: String
    ): String? {
        if (type != NoisePayloadType.DELIVERED && type != NoisePayloadType.READ_RECEIPT) {
            return null
        }

        try {
            val payload = ByteArray(1 + messageID.toByteArray().size)
            payload[0] = type.value.toByte()
            val messageIDBytes = messageID.toByteArray()
            // System.arraycopy(messageIDBytes, 0, payload, 1, messageIDBytes.size)
            messageIDBytes.copyInto(
                destination = payload,
                destinationOffset = 1,
                startIndex = 0,
                endIndex = messageIDBytes.size
            )

            val recipientIDHex = normalizeRecipientPeerID(recipientPeerID)

            val packet = BitchatPacket(
                version = 1u,
                type = MessageType.NOISE_ENCRYPTED.value,
                senderID = hexStringToByteArray(senderPeerID),
                recipientID = hexStringToByteArray(recipientIDHex),
                timestamp = Clock.System.now().toEpochMilliseconds().toULong(),
                payload = payload,
                signature = null,
                ttl = MESSAGE_TTL_HOPS
            )

            val data = packet.toBinaryData() ?: return null
            return "bitchat1:" + base64URLEncode(data)
        } catch (e: Exception) {
            // Log.e(TAG, "Failed to encode ACK for Nostr: ${e.message}")
            return null
        }
    }

    /**
     * Build a `bitchat1:` ACK (delivered/read) without an embedded recipient peer ID (geohash DMs).
     */
    fun encodeAckForNostrNoRecipient(
        type: NoisePayloadType,
        messageID: String,
        senderPeerID: String
    ): String? {
        if (type != NoisePayloadType.DELIVERED && type != NoisePayloadType.READ_RECEIPT) {
            return null
        }

        try {
            val payload = ByteArray(1 + messageID.toByteArray().size)
            payload[0] = type.value.toByte()
            val messageIDBytes = messageID.toByteArray()
            messageIDBytes.copyInto(
                destination = payload,
                destinationOffset = 1,
                startIndex = 0,
                endIndex = messageIDBytes.size
            )
            // System.arraycopy(messageIDBytes, 0, payload, 1, messageIDBytes.size)

            val packet = BitchatPacket(
                version = 1u,
                type = MessageType.NOISE_ENCRYPTED.value,
                senderID = hexStringToByteArray(senderPeerID),
                recipientID = null, // No recipient for geohash DMs
                timestamp = Clock.System.now().toEpochMilliseconds().toULong(),
                payload = payload,
                signature = null,
                ttl = MESSAGE_TTL_HOPS
            )

            val data = packet.toBinaryData() ?: return null
            return "bitchat1:" + base64URLEncode(data)
        } catch (e: Exception) {
            // Log.e(TAG, "Failed to encode ACK for Nostr (no recipient): ${e.message}")
            return null
        }
    }

    /**
     * Build a `bitchat1:` payload without an embedded recipient peer ID (used for geohash DMs).
     */
    fun encodePMForNostrNoRecipient(
        content: String,
        messageID: String,
        senderPeerID: String
    ): String? {
        try {
            val pm = PrivateMessagePacket(messageID = messageID, content = content)
            val tlv = pm.encode() ?: return null

            val payload = ByteArray(1 + tlv.size)
            payload[0] = NoisePayloadType.PRIVATE_MESSAGE.value.toByte()
            tlv.copyInto(
                destination = payload,
                destinationOffset = 1,
                startIndex = 0,
                endIndex = tlv.size
            )
            // System.arraycopy(tlv, 0, payload, 1, tlv.size)

            val packet = BitchatPacket(
                version = 1u,
                type = MessageType.NOISE_ENCRYPTED.value,
                senderID = hexStringToByteArray(senderPeerID),
                recipientID = null, // No recipient for geohash DMs
                timestamp = Clock.System.now().toEpochMilliseconds().toULong(),
                payload = payload,
                signature = null,
                ttl = MESSAGE_TTL_HOPS
            )

            val data = packet.toBinaryData() ?: return null
            return "bitchat1:" + base64URLEncode(data)
        } catch (e: Exception) {
            // Log.e(TAG, "Failed to encode PM for Nostr (no recipient): ${e.message}")
            return null
        }
    }

    /**
     * Normalize recipient peer ID (matches iOS implementation)
     */
    private fun normalizeRecipientPeerID(recipientPeerID: String): String {
        try {
            val maybeData = hexStringToByteArray(recipientPeerID)
            return when (maybeData.size) {
                32 -> {
                    // Treat as Noise static public key; derive peerID from fingerprint
                    // For now, return first 8 bytes as hex (simplified)
                    maybeData.toHexString()
                }

                8 -> {
                    // Already an 8-byte peer ID
                    recipientPeerID
                }

                else -> {
                    // Fallback: return as-is (expecting 16 hex chars)
                    recipientPeerID
                }
            }
        } catch (e: Exception) {
            // Fallback: return as-is
            return recipientPeerID
        }
    }

    /**
     * Base64url encode without padding (matches iOS implementation)
     */
    private fun base64URLEncode(data: ByteArray): String {
        val b64 = encode(data)
        return b64
            .replace("+", "-")
            .replace("/", "_")
            .replace("=", "")
    }

    /**
     * Convert hex string to byte array
     */
    private fun hexStringToByteArray(hexString: String): ByteArray {
        if (hexString.length % 2 != 0) {
            return ByteArray(8) // Return 8-byte array filled with zeros
        }

        val result = ByteArray(8) { 0 } // Exactly 8 bytes like iOS
        var tempID = hexString
        var index = 0

        while (tempID.length >= 2 && index < 8) {
            val hexByte = tempID.substring(0, 2)
            val byte = hexByte.toIntOrNull(16)?.toByte()
            if (byte != null) {
                result[index] = byte
            }
            tempID = tempID.substring(2)
            index++
        }

        return result
    }
}
