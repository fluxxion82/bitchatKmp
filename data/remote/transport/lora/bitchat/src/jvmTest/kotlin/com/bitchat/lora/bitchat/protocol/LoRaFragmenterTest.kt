package com.bitchat.lora.bitchat.protocol

import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoRaFragmenterTest {

    @Test
    fun `small message produces single frame`() = runTest {
        val fragmenter = LoRaFragmenter()
        val data = "Hello, World!".toByteArray()

        val frames = fragmenter.fragment(data)

        assertEquals(1, frames.size)
        assertEquals(0.toUByte(), frames[0].fragmentIndex)
        assertEquals(1.toUByte(), frames[0].totalFragments)
        assertTrue(data.contentEquals(frames[0].payload))
    }

    @Test
    fun `exactly MAX_PAYLOAD bytes produces single frame`() = runTest {
        val fragmenter = LoRaFragmenter()
        val data = ByteArray(LoRaFrame.MAX_PAYLOAD) { it.toByte() }

        val frames = fragmenter.fragment(data)

        assertEquals(1, frames.size)
        assertEquals(LoRaFrame.MAX_PAYLOAD, frames[0].payload.size)
    }

    @Test
    fun `MAX_PAYLOAD + 1 bytes produces two frames`() = runTest {
        val fragmenter = LoRaFragmenter()
        val data = ByteArray(LoRaFrame.MAX_PAYLOAD + 1) { it.toByte() }

        val frames = fragmenter.fragment(data)

        assertEquals(2, frames.size)

        // First frame should be full
        assertEquals(0.toUByte(), frames[0].fragmentIndex)
        assertEquals(2.toUByte(), frames[0].totalFragments)
        assertEquals(LoRaFrame.MAX_PAYLOAD, frames[0].payload.size)

        // Second frame should have 1 byte
        assertEquals(1.toUByte(), frames[1].fragmentIndex)
        assertEquals(2.toUByte(), frames[1].totalFragments)
        assertEquals(1, frames[1].payload.size)
    }

    @Test
    fun `large message fragments correctly`() = runTest {
        val fragmenter = LoRaFragmenter()
        val data = ByteArray(1000) { it.toByte() }

        val frames = fragmenter.fragment(data)

        // 1000 / 232 = 4.31, so 5 frames
        assertEquals(5, frames.size)

        // All frames should have the same messageId
        val messageId = frames[0].messageId
        assertTrue(frames.all { it.messageId == messageId })

        // All frames should have totalFragments = 5
        assertTrue(frames.all { it.totalFragments == 5.toUByte() })

        // Fragment indices should be sequential
        for (i in frames.indices) {
            assertEquals(i.toUByte(), frames[i].fragmentIndex)
        }

        // Reassemble and verify
        val reassembled = ByteArray(data.size)
        var offset = 0
        for (frame in frames) {
            frame.payload.copyInto(reassembled, offset)
            offset += frame.payload.size
        }
        assertTrue(data.contentEquals(reassembled))
    }

    @Test
    fun `empty data returns empty list`() = runTest {
        val fragmenter = LoRaFragmenter()
        val frames = fragmenter.fragment(byteArrayOf())
        assertTrue(frames.isEmpty())
    }

    @Test
    fun `message IDs increment`() = runTest {
        val fragmenter = LoRaFragmenter()
        fragmenter.resetMessageIdCounter()

        val frames1 = fragmenter.fragment("A".toByteArray())
        val frames2 = fragmenter.fragment("B".toByteArray())
        val frames3 = fragmenter.fragment("C".toByteArray())

        assertEquals(0.toUShort(), frames1[0].messageId)
        assertEquals(1.toUShort(), frames2[0].messageId)
        assertEquals(2.toUShort(), frames3[0].messageId)
    }

    @Test
    fun `calculateFragmentCount returns correct values`() {
        val fragmenter = LoRaFragmenter()

        assertEquals(0, fragmenter.calculateFragmentCount(0))
        assertEquals(1, fragmenter.calculateFragmentCount(1))
        assertEquals(1, fragmenter.calculateFragmentCount(232))
        assertEquals(2, fragmenter.calculateFragmentCount(233))
        assertEquals(2, fragmenter.calculateFragmentCount(464))
        assertEquals(3, fragmenter.calculateFragmentCount(465))
        assertEquals(5, fragmenter.calculateFragmentCount(1000))
    }

    @Test
    fun `frames from same message have same messageId`() = runTest {
        val fragmenter = LoRaFragmenter()
        val data = ByteArray(500) { 0 }

        val frames = fragmenter.fragment(data)

        val messageId = frames[0].messageId
        assertTrue(frames.all { it.messageId == messageId })
    }

    @Test
    fun `payload boundaries are correct`() = runTest {
        val fragmenter = LoRaFragmenter()
        // Create data with recognizable patterns at boundaries
        val data = ByteArray(LoRaFrame.MAX_PAYLOAD * 2 + 50) { i ->
            when {
                i == 0 -> 0xAA.toByte()
                i == LoRaFrame.MAX_PAYLOAD - 1 -> 0xBB.toByte()
                i == LoRaFrame.MAX_PAYLOAD -> 0xCC.toByte()
                i == LoRaFrame.MAX_PAYLOAD * 2 - 1 -> 0xDD.toByte()
                i == LoRaFrame.MAX_PAYLOAD * 2 -> 0xEE.toByte()
                else -> 0x00
            }
        }

        val frames = fragmenter.fragment(data)

        assertEquals(3, frames.size)

        // Check first frame boundaries
        assertEquals(0xAA.toByte(), frames[0].payload[0])
        assertEquals(0xBB.toByte(), frames[0].payload[LoRaFrame.MAX_PAYLOAD - 1])

        // Check second frame boundaries
        assertEquals(0xCC.toByte(), frames[1].payload[0])
        assertEquals(0xDD.toByte(), frames[1].payload[LoRaFrame.MAX_PAYLOAD - 1])

        // Check third frame
        assertEquals(0xEE.toByte(), frames[2].payload[0])
        assertEquals(50, frames[2].payload.size)
    }
}
