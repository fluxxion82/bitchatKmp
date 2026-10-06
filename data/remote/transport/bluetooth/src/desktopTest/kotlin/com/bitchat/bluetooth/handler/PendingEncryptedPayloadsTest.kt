package com.bitchat.bluetooth.handler

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PendingEncryptedPayloadsTest {

    private fun store(
        perPeer: Int = 4,
        bytesPerPeer: Int = 1_000,
        peers: Int = 128,
        maxAgeMs: Long = 1_000
    ) = PendingEncryptedPayloads(
        maxPerPeer = perPeer,
        maxBytesPerPeer = bytesPerPeer,
        maxPeers = peers,
        maxAgeMs = maxAgeMs
    )

    @Test
    fun onePeerKeepsNoMoreThanItsPayloadLimit() {
        val pending = store(perPeer = 2)
        assertTrue(pending.offer("a", byteArrayOf(1), 0))
        assertTrue(pending.offer("a", byteArrayOf(2), 0))
        assertFalse(pending.offer("a", byteArrayOf(3), 0))
        assertEquals(2, pending.count)
    }

    @Test
    fun onePeerKeepsNoMoreThanItsByteLimit() {
        val pending = store(bytesPerPeer = 4)
        assertTrue(pending.offer("a", byteArrayOf(1, 1), 0))
        assertTrue(pending.offer("a", byteArrayOf(2, 2), 0))
        assertFalse(pending.offer("a", byteArrayOf(3), 0))
        assertEquals(4, pending.bytes)
    }

    @Test
    fun aPayloadLargerThanAPeersByteLimitIsRefusedOutright() {
        val pending = store(bytesPerPeer = 4)
        assertFalse(pending.offer("a", ByteArray(5), 0))
        assertEquals(0, pending.count)
        assertEquals(0, pending.bytes)
    }

    @Test
    fun onePeerAtItsLimitsDoesNotAffectAnotherPeer() {
        val pending = store(perPeer = 1, bytesPerPeer = 2)
        assertTrue(pending.offer("a", byteArrayOf(1, 1), 0))
        assertFalse(pending.offer("a", byteArrayOf(2), 0))
        assertTrue(pending.offer("b", byteArrayOf(3, 3), 0))
        assertEquals(2, pending.count)
        assertEquals(4, pending.bytes)
    }

    @Test
    fun the129thPeerIsRefusedUntilATakenPeerFreesItsPlace() {
        val pending = store(peers = 128)
        repeat(128) { assertTrue(pending.offer("peer-$it", byteArrayOf(1), 0)) }
        assertFalse(pending.offer("waiting", byteArrayOf(2), 0))
        pending.take("peer-0", 0)
        assertTrue(pending.offer("waiting", byteArrayOf(2), 0))
    }

    @Test
    fun the129thPeerIsAcceptedAfterADropOrExpiryFreesAPlace() {
        val dropped = store(peers = 1)
        assertTrue(dropped.offer("a", byteArrayOf(1), 0))
        dropped.drop("a")
        assertTrue(dropped.offer("b", byteArrayOf(2), 0))

        val expired = store(peers = 1, maxAgeMs = 10)
        assertTrue(expired.offer("a", byteArrayOf(1), 0))
        assertTrue(expired.offer("b", byteArrayOf(2), 10))
    }

    @Test
    fun aRefusalNeverLosesAnythingAlreadyKept() {
        val pending = store(perPeer = 2, bytesPerPeer = 4, peers = 1)
        val first = byteArrayOf(1, 1)
        val second = byteArrayOf(2, 2)
        assertTrue(pending.offer("a", first, 0))
        assertTrue(pending.offer("a", second, 1))
        assertFalse(pending.offer("a", byteArrayOf(3), 2))
        assertFalse(pending.offer("b", byteArrayOf(4), 2))

        val taken = pending.take("a", 2)
        assertEquals(2, taken.size)
        assertContentEquals(first, taken[0])
        assertContentEquals(second, taken[1])
    }

    @Test
    fun takePreservesArrivalOrder() {
        val pending = store()
        val first = byteArrayOf(1)
        val second = byteArrayOf(2)
        assertTrue(pending.offer("a", first, 0))
        assertTrue(pending.offer("a", second, 1))

        val taken = pending.take("a", 1)
        assertEquals(2, taken.size)
        assertContentEquals(first, taken[0])
        assertContentEquals(second, taken[1])
    }

    @Test
    fun expiredPayloadsAreForgottenAndTheirPeerPlaceIsFreed() {
        val pending = store(peers = 1, maxAgeMs = 10)
        assertTrue(pending.offer("a", byteArrayOf(1), 0))
        assertTrue(pending.take("a", 10).isEmpty())
        assertEquals(0, pending.count)
        assertEquals(0, pending.bytes)
        assertTrue(pending.offer("b", byteArrayOf(2), 10))
    }
}
