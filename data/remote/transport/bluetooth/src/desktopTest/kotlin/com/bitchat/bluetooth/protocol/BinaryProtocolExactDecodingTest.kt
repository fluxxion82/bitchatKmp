package com.bitchat.bluetooth.protocol

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BinaryProtocolExactDecodingTest {
    @Test
    fun onlyOneUnpaddedUnsignedUncompressedPacketDecodesExactly() {
        val packet = packet(payload = byteArrayOf(1, 2, 3))
        val raw = unpadded(packet)
        val padded = requireNotNull(BinaryProtocol.encode(packet))
        val withTrailingByte = raw + byteArrayOf(0)
        val signed = unpadded(packet.copy(signature = ByteArray(64) { 7 }))
        val compressed = unpadded(packet(payload = ByteArray(200) { 1 }))

        assertNotNull(BinaryProtocol.decodeExact(raw))
        assertNull(BinaryProtocol.decodeExact(withTrailingByte))
        assertNull(BinaryProtocol.decodeExact(padded))
        assertNull(BinaryProtocol.decodeExact(signed))
        assertNull(BinaryProtocol.decodeExact(compressed))

        assertNotNull(BinaryProtocol.decode(raw))
        assertNotNull(BinaryProtocol.decode(withTrailingByte))
        assertNotNull(BinaryProtocol.decode(padded))
        assertNotNull(BinaryProtocol.decode(signed))
        assertNotNull(BinaryProtocol.decode(compressed))
        assertTrue((compressed[11].toInt() and BinaryProtocol.Flags.IS_COMPRESSED.toInt()) != 0)
    }

    @Test
    fun aPacketWithAnUnknownFlagOrWithoutAPayloadDoesNotDecodeExactly() {
        val raw = unpadded(packet(payload = byteArrayOf(1, 2, 3)))
        val unknownFlag = raw.copyOf().also { it[11] = (it[11].toInt() or 0x80).toByte() }
        val empty = unpadded(packet(payload = ByteArray(0)))

        assertNotNull(BinaryProtocol.decodeExact(raw))
        assertNull(BinaryProtocol.decodeExact(unknownFlag))
        assertNull(BinaryProtocol.decodeExact(empty))
        // The ordinary decoder is as tolerant as it was.
        assertNotNull(BinaryProtocol.decode(unknownFlag))
        assertNotNull(BinaryProtocol.decode(empty))
    }

    @Test
    fun aVersionTwoPacketDoesNotDecodeExactly() {
        // The larger length field exists for payloads no radio frame could hold; the radio carries version 1.
        val versionTwo = unpadded(packet(payload = byteArrayOf(1, 2, 3)).copy(version = 2u))

        assertNull(BinaryProtocol.decodeExact(versionTwo))
        assertNotNull(BinaryProtocol.decode(versionTwo))
    }

    private fun packet(payload: ByteArray): BitchatPacket = BitchatPacket(
        type = MessageType.NOISE_HANDSHAKE.value,
        senderID = "0102030405060708".hexToBytes(),
        recipientID = "1112131415161718".hexToBytes(),
        timestamp = 1u,
        payload = payload,
        ttl = 0u,
    )

    private fun unpadded(packet: BitchatPacket): ByteArray =
        MessagePadding.unpad(requireNotNull(BinaryProtocol.encode(packet)))

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
