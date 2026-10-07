package com.bitchat.bluetooth.protocol

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertEquals

class LoRaIngressTest {
    // With letters, so that the spelling of this device's id in another case is another string.
    private val mine = "a1b2c3d4e5f60708"
    private val sender = "1112131415161718"

    @Test
    fun rejectsEmptyAndOversizeDataBeforeDecoding() {
        assertRejected(ByteArray(0), LoRaRejection.TOO_LARGE)
        assertRejected(ByteArray(MAX_LORA_PACKET_BYTES + 1), LoRaRejection.TOO_LARGE)
    }

    @Test
    fun rejectsMalformedDataAfterTheSizeCheck() {
        assertRejected(byteArrayOf(1), LoRaRejection.NOT_A_PACKET)
    }

    @Test
    fun rejectsPacketsNotAddressedToThisDevice() {
        assertRejected(raw(recipient = "2122232425262728"), LoRaRejection.NOT_FOR_THIS_DEVICE)
        assertRejected(raw(recipient = null), LoRaRejection.NOT_FOR_THIS_DEVICE)
        assertRejected(raw(recipient = SpecialRecipients.BROADCAST), LoRaRejection.NOT_FOR_THIS_DEVICE)
    }

    @Test
    fun rejectsPacketsClaimingThisDevicesSenderId() {
        assertRejected(raw(sender = mine), LoRaRejection.FROM_THIS_DEVICE)
    }

    @Test
    fun rejectsEveryNonNoiseMessageType() {
        for (type in MessageType.entries - MessageType.NOISE_HANDSHAKE - MessageType.NOISE_ENCRYPTED) {
            assertRejected(raw(type = type), LoRaRejection.WRONG_TYPE)
        }
    }

    @Test
    fun rejectsNoisePacketsWithAnyNonZeroTtl() {
        assertRejected(raw(ttl = 1u), LoRaRejection.WRONG_TTL)
    }

    @Test
    fun acceptsBothNoiseTypesAndComparesMyPeerIdWithoutCase() {
        for (type in listOf(MessageType.NOISE_HANDSHAKE, MessageType.NOISE_ENCRYPTED)) {
            val accepted = assertIs<LoRaIngressResult.Accepted>(admitFromLoRa(raw(type = type), mine.uppercase()))
            assertEquals(sender, accepted.senderPeerID)
            assertEquals(type.value, accepted.packet.type)
        }
    }

    @Test
    fun acceptsAValidPacketAtTheSingleFrameLimit() {
        val data = raw(payload = ByteArray(202) { it.toByte() })
        assertEquals(MAX_LORA_PACKET_BYTES, data.size)
        assertIs<LoRaIngressResult.Accepted>(admitFromLoRa(data, mine))
    }

    private fun assertRejected(data: ByteArray, reason: LoRaRejection) {
        assertEquals(reason, assertIs<LoRaIngressResult.Rejected>(admitFromLoRa(data, mine)).reason)
    }

    private fun raw(
        type: MessageType = MessageType.NOISE_HANDSHAKE,
        sender: String = this.sender,
        recipient: Any? = mine,
        ttl: UByte = 0u,
        payload: ByteArray = byteArrayOf(1, 2, 3),
    ): ByteArray {
        val recipientID = when (recipient) {
            null -> null
            is String -> recipient.hexToBytes()
            is ByteArray -> recipient
            else -> error("unsupported recipient")
        }
        val packet = BitchatPacket(
            type = type.value,
            senderID = sender.hexToBytes(),
            recipientID = recipientID,
            timestamp = 1u,
            payload = payload,
            ttl = ttl,
        )
        return MessagePadding.unpad(requireNotNull(BinaryProtocol.encode(packet)))
    }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
