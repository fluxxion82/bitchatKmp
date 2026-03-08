package com.bitchat.lora.bitchat.protocol

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class LoRaFrameTest {

    @Test
    fun `toBytes and fromBytes round-trip for single fragment`() {
        val payload = "Hello, LoRa!".toByteArray()
        val frame = LoRaFrame(
            messageId = 0x1234u,
            fragmentIndex = 0u,
            totalFragments = 1u,
            flags = 0u,
            payload = payload
        )

        val bytes = frame.toBytes()
        val parsed = LoRaFrame.fromBytes(bytes)

        assertNotNull(parsed)
        assertEquals(frame.messageId, parsed.messageId)
        assertEquals(frame.fragmentIndex, parsed.fragmentIndex)
        assertEquals(frame.totalFragments, parsed.totalFragments)
        assertEquals(frame.flags, parsed.flags)
        assertTrue(frame.payload.contentEquals(parsed.payload))
    }

    @Test
    fun `toBytes and fromBytes round-trip for multi-fragment`() {
        val payload = ByteArray(100) { it.toByte() }
        val frame = LoRaFrame(
            messageId = 0xABCDu,
            fragmentIndex = 2u,
            totalFragments = 5u,
            flags = 0x0Fu,
            payload = payload
        )

        val bytes = frame.toBytes()
        val parsed = LoRaFrame.fromBytes(bytes)

        assertNotNull(parsed)
        assertEquals(frame.messageId, parsed.messageId)
        assertEquals(frame.fragmentIndex, parsed.fragmentIndex)
        assertEquals(frame.totalFragments, parsed.totalFragments)
        assertEquals(frame.flags, parsed.flags)
        assertTrue(frame.payload.contentEquals(parsed.payload))
    }

    @Test
    fun `header is exactly 5 bytes`() {
        val payload = byteArrayOf(1, 2, 3)
        val frame = LoRaFrame(
            messageId = 0u,
            fragmentIndex = 0u,
            totalFragments = 1u,
            flags = 0u,
            payload = payload
        )

        val bytes = frame.toBytes()
        assertEquals(LoRaFrame.HEADER_SIZE + payload.size, bytes.size)
        assertEquals(8, bytes.size)
    }

    @Test
    fun `message ID is big-endian`() {
        val frame = LoRaFrame(
            messageId = 0x1234u,
            fragmentIndex = 0u,
            totalFragments = 1u,
            flags = 0u,
            payload = byteArrayOf()
        )

        val bytes = frame.toBytes()
        assertEquals(0x12.toByte(), bytes[0])
        assertEquals(0x34.toByte(), bytes[1])
    }

    @Test
    fun `fromBytes returns null for too-short data`() {
        val shortData = byteArrayOf(1, 2, 3, 4) // 4 bytes, need at least 5
        assertNull(LoRaFrame.fromBytes(shortData))
    }

    @Test
    fun `fromBytes returns null for too-long data`() {
        val longData = ByteArray(LoRaFrame.MAX_FRAME_SIZE + 1) { 0 }
        longData[2] = 0 // fragIndex
        longData[3] = 1 // totalFragments
        assertNull(LoRaFrame.fromBytes(longData))
    }

    @Test
    fun `fromBytes returns null for invalid fragment indices`() {
        val data = byteArrayOf(
            0, 1,    // messageId
            5,       // fragmentIndex = 5
            3,       // totalFragments = 3 (invalid: index >= total)
            0,       // flags
            1, 2, 3  // payload
        )
        assertNull(LoRaFrame.fromBytes(data))
    }

    @Test
    fun `fromBytes returns null for zero total fragments`() {
        val data = byteArrayOf(
            0, 1,    // messageId
            0,       // fragmentIndex = 0
            0,       // totalFragments = 0 (invalid)
            0,       // flags
            1, 2, 3  // payload
        )
        assertNull(LoRaFrame.fromBytes(data))
    }

    @Test
    fun `constructor rejects payload exceeding MAX_PAYLOAD`() {
        val oversizedPayload = ByteArray(LoRaFrame.MAX_PAYLOAD + 1) { 0 }

        assertFailsWith<IllegalArgumentException> {
            LoRaFrame(
                messageId = 0u,
                fragmentIndex = 0u,
                totalFragments = 1u,
                flags = 0u,
                payload = oversizedPayload
            )
        }
    }

    @Test
    fun `constructor rejects fragmentIndex greater than or equal to totalFragments`() {
        assertFailsWith<IllegalArgumentException> {
            LoRaFrame(
                messageId = 0u,
                fragmentIndex = 3u,
                totalFragments = 3u,
                flags = 0u,
                payload = byteArrayOf()
            )
        }
    }

    @Test
    fun `empty payload is valid`() {
        val frame = LoRaFrame(
            messageId = 0u,
            fragmentIndex = 0u,
            totalFragments = 1u,
            flags = 0u,
            payload = byteArrayOf()
        )

        val bytes = frame.toBytes()
        assertEquals(LoRaFrame.HEADER_SIZE, bytes.size)

        val parsed = LoRaFrame.fromBytes(bytes)
        assertNotNull(parsed)
        assertEquals(0, parsed.payload.size)
    }

    @Test
    fun `max payload size is allowed`() {
        val maxPayload = ByteArray(LoRaFrame.MAX_PAYLOAD) { it.toByte() }
        val frame = LoRaFrame(
            messageId = 0xFFFFu,
            fragmentIndex = 0u,
            totalFragments = 1u,
            flags = 0xFFu,
            payload = maxPayload
        )

        val bytes = frame.toBytes()
        assertEquals(LoRaFrame.MAX_FRAME_SIZE, bytes.size)

        val parsed = LoRaFrame.fromBytes(bytes)
        assertNotNull(parsed)
        assertTrue(maxPayload.contentEquals(parsed.payload))
    }

    @Test
    fun `equals and hashCode work correctly`() {
        val frame1 = LoRaFrame(
            messageId = 100u,
            fragmentIndex = 0u,
            totalFragments = 1u,
            flags = 0u,
            payload = byteArrayOf(1, 2, 3)
        )

        val frame2 = LoRaFrame(
            messageId = 100u,
            fragmentIndex = 0u,
            totalFragments = 1u,
            flags = 0u,
            payload = byteArrayOf(1, 2, 3)
        )

        assertEquals(frame1, frame2)
        assertEquals(frame1.hashCode(), frame2.hashCode())
    }

    @Test
    fun `toString is human readable`() {
        val frame = LoRaFrame(
            messageId = 42u,
            fragmentIndex = 1u,
            totalFragments = 3u,
            flags = 5u,
            payload = ByteArray(50)
        )

        val str = frame.toString()
        assertTrue(str.contains("42"))
        assertTrue(str.contains("1/3"))
        assertTrue(str.contains("50"))
    }
}
