package com.bitchat.api.dto.mapper

import com.bitchat.domain.chat.model.BitchatFilePacket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BitchatFilePacketMapperTest {
    @Test
    fun contentAtTheCapDecodesAndOneByteOverDoesNot() {
        val atCap = packet(BitchatFilePacket.MAX_CONTENT_BYTES).toWireFormat()!!
        assertEquals(BitchatFilePacket.MAX_CONTENT_BYTES, atCap.toBitchatFilePacket()!!.content.size)

        val overCap = packet(BitchatFilePacket.MAX_CONTENT_BYTES + 1).toWireFormat()!!
        assertNull(overCap.toBitchatFilePacket())
    }

    @Test
    fun manyContentTlvsPastTheCapDoNotDecode() {
        val header = packet(0).toWireFormat()!!
        val records = (listOf(header) + List(16) { contentTlv(65_535) })
            .fold(ByteArray(0)) { all, record -> all + record }
        assertNull(records.toBitchatFilePacket())
    }

    private fun packet(size: Int) = BitchatFilePacket("file", size.toLong(), "application/octet-stream", ByteArray(size))
    private fun contentTlv(size: Int): ByteArray {
        require(size <= 65_535)
        return byteArrayOf(FilePacketTLVType.CONTENT.v.toByte(), (size ushr 8).toByte(), size.toByte()) + ByteArray(size)
    }
}
