package com.bitchat.bluetooth.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The numbers of the packets a peer says it read: a count, then four bytes each, big endian. */
class DeliveredNumbersTest {
    @Test
    fun aNumberIsFourBytesBigEndianAfterTheCount() {
        val bytes = requireNotNull(DeliveredNumbers.encode(listOf(0x01020304L, 0xffffffffL, 0L)))

        assertContentEquals(byteArrayOf(3, 1, 2, 3, 4, -1, -1, -1, -1, 0, 0, 0, 0), bytes)
        assertEquals(listOf(0x01020304L, 0xffffffffL, 0L), DeliveredNumbers.decode(bytes))
    }

    @Test
    fun oneToEightNumbersGoInAndComeOutAsTheyWere() {
        val eight = listOf(0L, 1L, 255L, 256L, 65_536L, 0x7fffffffL, 0x80000000L, 0xffffffffL)

        assertEquals(listOf(7L), DeliveredNumbers.decode(requireNotNull(DeliveredNumbers.encode(listOf(7L)))))
        assertEquals(eight, DeliveredNumbers.decode(requireNotNull(DeliveredNumbers.encode(eight))))
        assertEquals(1 + 8 * 4, requireNotNull(DeliveredNumbers.encode(eight)).size)
    }

    @Test
    fun noNumbersMoreThanEightAndNumbersThatDoNotFitFourBytesAreNotEncoded() {
        assertNull(DeliveredNumbers.encode(emptyList()))
        assertNull(DeliveredNumbers.encode(List(9) { it.toLong() }))
        assertNull(DeliveredNumbers.encode(listOf(1L, -1L)))
        assertNull(DeliveredNumbers.encode(listOf(1L, 0x1_0000_0000L)))
    }

    @Test
    fun whatIsNotACountOfOneToEightWithExactlyItsNumbersIsNotDecoded() {
        assertNull(DeliveredNumbers.decode(byteArrayOf()))
        assertNull(DeliveredNumbers.decode(byteArrayOf(0)))
        // Nine numbers, all there.
        assertNull(DeliveredNumbers.decode(ByteArray(1 + 9 * 4).also { it[0] = 9 }))
        // Two announced, one and three quarters there; one announced, one byte more.
        assertNull(DeliveredNumbers.decode(byteArrayOf(2, 0, 0, 0, 1, 0, 0, 0)))
        assertNull(DeliveredNumbers.decode(byteArrayOf(1, 0, 0, 0, 1, 0)))
        // A count of 200, all two hundred there.
        assertNull(DeliveredNumbers.decode(ByteArray(1 + 200 * 4).also { it[0] = 200.toByte() }))
    }

    @Test
    fun aPacketsNumberIsTheFourBytesAtItsStartReadWithoutASign() {
        assertEquals(0x01020304L, DeliveredNumbers.packetNumber(byteArrayOf(1, 2, 3, 4, 5)))
        assertEquals(0xffffffffL, DeliveredNumbers.packetNumber(byteArrayOf(-1, -1, -1, -1)))
        assertNull(DeliveredNumbers.packetNumber(byteArrayOf(1, 2, 3)))
    }
}
