package com.bitchat.lora.bitchat.protocol

import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoRaAssemblerTest {

    @Test
    fun `single-frame message returns immediately`() = runTest {
        val assembler = LoRaAssembler()

        val frame = LoRaFrame(
            messageId = 1u,
            fragmentIndex = 0u,
            totalFragments = 1u,
            flags = 0u,
            payload = "Hello".toByteArray()
        )

        val result = assembler.processFrame(frame)

        assertNotNull(result)
        assertEquals("Hello", String(result))
    }

    @Test
    fun `multi-frame message assembles in order`() = runTest {
        val assembler = LoRaAssembler()

        val frame0 = LoRaFrame(
            messageId = 1u,
            fragmentIndex = 0u,
            totalFragments = 3u,
            flags = 0u,
            payload = "AAA".toByteArray()
        )
        val frame1 = LoRaFrame(
            messageId = 1u,
            fragmentIndex = 1u,
            totalFragments = 3u,
            flags = 0u,
            payload = "BBB".toByteArray()
        )
        val frame2 = LoRaFrame(
            messageId = 1u,
            fragmentIndex = 2u,
            totalFragments = 3u,
            flags = 0u,
            payload = "CCC".toByteArray()
        )

        assertNull(assembler.processFrame(frame0))
        assertNull(assembler.processFrame(frame1))

        val result = assembler.processFrame(frame2)
        assertNotNull(result)
        assertEquals("AAABBBCCC", String(result))
    }

    @Test
    fun `multi-frame message assembles out of order`() = runTest {
        val assembler = LoRaAssembler()

        val frame0 = LoRaFrame(
            messageId = 1u,
            fragmentIndex = 0u,
            totalFragments = 3u,
            flags = 0u,
            payload = "AAA".toByteArray()
        )
        val frame1 = LoRaFrame(
            messageId = 1u,
            fragmentIndex = 1u,
            totalFragments = 3u,
            flags = 0u,
            payload = "BBB".toByteArray()
        )
        val frame2 = LoRaFrame(
            messageId = 1u,
            fragmentIndex = 2u,
            totalFragments = 3u,
            flags = 0u,
            payload = "CCC".toByteArray()
        )

        // Send out of order: 2, 0, 1
        assertNull(assembler.processFrame(frame2))
        assertNull(assembler.processFrame(frame0))

        val result = assembler.processFrame(frame1)
        assertNotNull(result)
        assertEquals("AAABBBCCC", String(result))
    }

    @Test
    fun `duplicate fragments are ignored`() = runTest {
        val assembler = LoRaAssembler()

        val frame0 = LoRaFrame(
            messageId = 1u,
            fragmentIndex = 0u,
            totalFragments = 2u,
            flags = 0u,
            payload = "AAA".toByteArray()
        )
        val frame1 = LoRaFrame(
            messageId = 1u,
            fragmentIndex = 1u,
            totalFragments = 2u,
            flags = 0u,
            payload = "BBB".toByteArray()
        )

        assertNull(assembler.processFrame(frame0))
        assertNull(assembler.processFrame(frame0)) // Duplicate

        val result = assembler.processFrame(frame1)
        assertNotNull(result)
        assertEquals("AAABBB", String(result))
    }

    @Test
    fun `duplicate for completed message is ignored`() = runTest {
        val assembler = LoRaAssembler()

        val frame = LoRaFrame(
            messageId = 1u,
            fragmentIndex = 0u,
            totalFragments = 1u,
            flags = 0u,
            payload = "Hello".toByteArray()
        )

        val result1 = assembler.processFrame(frame)
        assertNotNull(result1)

        // Same message ID after completion should be ignored
        val result2 = assembler.processFrame(frame)
        assertNull(result2)
    }

    @Test
    fun `multiple concurrent assemblies`() = runTest {
        val assembler = LoRaAssembler()

        // Message 1, fragment 0
        val msg1_f0 = LoRaFrame(1u, 0u, 2u, 0u, "1A".toByteArray())
        // Message 2, fragment 0
        val msg2_f0 = LoRaFrame(2u, 0u, 2u, 0u, "2A".toByteArray())
        // Message 1, fragment 1
        val msg1_f1 = LoRaFrame(1u, 1u, 2u, 0u, "1B".toByteArray())
        // Message 2, fragment 1
        val msg2_f1 = LoRaFrame(2u, 1u, 2u, 0u, "2B".toByteArray())

        assertNull(assembler.processFrame(msg1_f0))
        assertNull(assembler.processFrame(msg2_f0))

        val result1 = assembler.processFrame(msg1_f1)
        assertNotNull(result1)
        assertEquals("1A1B", String(result1))

        val result2 = assembler.processFrame(msg2_f1)
        assertNotNull(result2)
        assertEquals("2A2B", String(result2))
    }

    @Test
    fun `assembly timeout removes incomplete assemblies`() = runTest {
        var currentTime = 0L
        val assembler = LoRaAssembler(
            assemblyTimeoutMs = 1000,
            timeProvider = { currentTime }
        )

        val frame0 = LoRaFrame(1u, 0u, 2u, 0u, "AAA".toByteArray())

        assertNull(assembler.processFrame(frame0))

        val stats1 = assembler.getStats()
        assertEquals(1, stats1.activeAssemblies)

        // Advance time past timeout
        currentTime = 2000L

        // Processing any frame triggers cleanup
        val unrelatedFrame = LoRaFrame(2u, 0u, 1u, 0u, "X".toByteArray())
        assembler.processFrame(unrelatedFrame)

        val stats2 = assembler.getStats()
        assertEquals(0, stats2.activeAssemblies)
    }

    @Test
    fun `max concurrent assemblies evicts oldest`() = runTest {
        val assembler = LoRaAssembler(maxConcurrentAssemblies = 3)

        // Start 3 assemblies
        for (i in 1..3) {
            val frame = LoRaFrame(i.toUShort(), 0u, 2u, 0u, "X".toByteArray())
            assembler.processFrame(frame)
        }

        val stats1 = assembler.getStats()
        assertEquals(3, stats1.activeAssemblies)

        // Start 4th assembly - should evict oldest (message 1)
        val frame4 = LoRaFrame(4u, 0u, 2u, 0u, "Y".toByteArray())
        assembler.processFrame(frame4)

        val stats2 = assembler.getStats()
        assertEquals(3, stats2.activeAssemblies)

        // Try to complete message 1 - should fail (was evicted)
        val frame1_complete = LoRaFrame(1u, 1u, 2u, 0u, "Z".toByteArray())
        val result = assembler.processFrame(frame1_complete)

        // Since msg 1 was evicted, this starts a new assembly (partial)
        // It won't complete because we're missing fragment 0
        assertNull(result)
    }

    @Test
    fun `clear removes all assemblies`() = runTest {
        val assembler = LoRaAssembler()

        // Start some assemblies
        for (i in 1..5) {
            val frame = LoRaFrame(i.toUShort(), 0u, 2u, 0u, "X".toByteArray())
            assembler.processFrame(frame)
        }

        val stats1 = assembler.getStats()
        assertEquals(5, stats1.activeAssemblies)

        assembler.clear()

        val stats2 = assembler.getStats()
        assertEquals(0, stats2.activeAssemblies)
        assertEquals(0, stats2.recentlyCompletedCount)
    }

    @Test
    fun `fragmenter and assembler round-trip`() = runTest {
        val fragmenter = LoRaFragmenter()
        val assembler = LoRaAssembler()

        val originalData = ByteArray(1000) { it.toByte() }

        val frames = fragmenter.fragment(originalData)
        assertEquals(5, frames.size)

        var result: ByteArray? = null
        for (frame in frames) {
            result = assembler.processFrame(frame)
        }

        assertNotNull(result)
        assertTrue(originalData.contentEquals(result))
    }

    @Test
    fun `fragmenter and assembler round-trip with out of order delivery`() = runTest {
        val fragmenter = LoRaFragmenter()
        val assembler = LoRaAssembler()

        val originalData = ByteArray(500) { it.toByte() }

        val frames = fragmenter.fragment(originalData)

        // Shuffle the frames
        val shuffled = frames.shuffled()

        var result: ByteArray? = null
        for (frame in shuffled) {
            val r = assembler.processFrame(frame)
            if (r != null) result = r
        }

        assertNotNull(result)
        assertTrue(originalData.contentEquals(result))
    }
}
