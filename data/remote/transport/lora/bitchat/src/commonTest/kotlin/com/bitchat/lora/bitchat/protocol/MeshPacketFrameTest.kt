package com.bitchat.lora.bitchat.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

class MeshPacketFrameTest {
    @Test
    fun roundTripsPacketsAtEverySupportedSize() {
        for (size in listOf(1, 100, LoRaFrame.MAX_PAYLOAD)) {
            val packet = ByteArray(size) { it.toByte() }

            val encoded = requireNotNull(MeshPacketFrame.encode(0x1234u, packet))

            assertContentEquals(packet, MeshPacketFrame.decode(encoded))
            assertNull(LoRaFrame.fromBytes(encoded))
        }
    }

    @Test
    fun refusesEmptyAndOversizePackets() {
        assertNull(MeshPacketFrame.encode(1u, ByteArray(0)))
        assertNull(MeshPacketFrame.encode(1u, ByteArray(LoRaFrame.MAX_PAYLOAD + 1)))
    }

    @Test
    fun decodeOnlyAcceptsTheExactPacketFrameShape() {
        val encoded = requireNotNull(MeshPacketFrame.encode(1u, byteArrayOf(1)))

        assertNull(MeshPacketFrame.decode(encoded.copyOf(5)))
        assertNull(MeshPacketFrame.decode(ByteArray(LoRaFrame.MAX_FRAME_SIZE + 1)))
        assertNull(MeshPacketFrame.decode(encoded.copyOf().also { it[2] = 1 }))
        assertNull(MeshPacketFrame.decode(encoded.copyOf().also { it[3] = 1 }))
        assertNull(MeshPacketFrame.decode(encoded.copyOf().also { it[4] = 0x00 }))
        assertNull(MeshPacketFrame.decode(encoded.copyOf().also { it[4] = 0x11 }))
        assertNull(MeshPacketFrame.decode(encoded.copyOf().also { it[4] = 0x30 }))
    }
}
