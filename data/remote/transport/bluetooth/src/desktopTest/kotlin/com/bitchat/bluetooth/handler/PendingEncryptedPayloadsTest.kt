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
        peersPerLink: Int = 16,
        maxAgeMs: Long = 1_000
    ) = PendingEncryptedPayloads(
        maxPerPeer = perPeer,
        maxBytesPerPeer = bytesPerPeer,
        maxPeers = peers,
        maxPeersPerLink = peersPerLink,
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
    fun theSeventeenthPeerOnOneLinkIsRefusedWhileAnotherLinkIsAccepted() {
        val pending = store(peers = 128, peersPerLink = 16)
        repeat(16) { assertTrue(pending.offer("peer-$it", byteArrayOf(1), 0, "first")) }
        assertFalse(pending.offer("waiting", byteArrayOf(2), 0, "first"))
        assertTrue(pending.offer("other-link", byteArrayOf(3), 0, "second"))
    }

    @Test
    fun aPeerAlreadyHoldingPayloadsCanKeepAnotherOnAFullLink() {
        val pending = store(perPeer = 2, peersPerLink = 1)
        assertTrue(pending.offer("held", byteArrayOf(1), 0, "first"))
        assertTrue(pending.offer("held", byteArrayOf(2), 1, "second"))
        assertEquals(2, pending.count)
    }

    @Test
    fun takingDroppingOrExpiringAFilledLinksPeerLetsAnotherPeerIn() {
        val taken = store(peersPerLink = 16)
        repeat(16) { assertTrue(taken.offer("peer-$it", byteArrayOf(1), 0, "first")) }
        assertContentEquals(byteArrayOf(1), taken.take("peer-0", 0).single().payload)
        assertTrue(taken.offer("waiting", byteArrayOf(2), 0, "first"))

        val dropped = store(peersPerLink = 16)
        repeat(16) { assertTrue(dropped.offer("peer-$it", byteArrayOf(1), 0, "first")) }
        dropped.drop("peer-0")
        assertTrue(dropped.offer("waiting", byteArrayOf(2), 0, "first"))

        val expired = store(peersPerLink = 16, maxAgeMs = 10)
        assertTrue(expired.offer("peer-0", byteArrayOf(1), 0, "first"))
        repeat(15) { assertTrue(expired.offer("peer-${it + 1}", byteArrayOf(1), 1, "first")) }
        assertTrue(expired.offer("waiting", byteArrayOf(2), 10, "first"))
    }

    @Test
    fun theTotalStillRefusesThe129thPeerAcrossEnoughLinks() {
        val pending = store(peers = 128, peersPerLink = 16)
        repeat(128) { assertTrue(pending.offer("peer-$it", byteArrayOf(1), 0, "link-${it / 16}")) }
        assertFalse(pending.offer("waiting", byteArrayOf(2), 0, "link-8"))
        pending.take("peer-0", 0)
        assertTrue(pending.offer("waiting", byteArrayOf(2), 0, "link-8"))
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
        val pending = store(perPeer = 2, bytesPerPeer = 4, peers = 2, peersPerLink = 1)
        val first = byteArrayOf(1, 1)
        val second = byteArrayOf(2, 2)
        assertTrue(pending.offer("a", first, 0, "first"))
        assertTrue(pending.offer("a", second, 1, "first"))
        assertFalse(pending.offer("a", byteArrayOf(3), 2, "first"))
        assertFalse(pending.offer("b", byteArrayOf(4), 2, "first"))
        assertEquals(2, pending.count)
        assertEquals(4, pending.bytes)

        val taken = pending.take("a", 2)
        assertEquals(2, taken.size)
        assertContentEquals(first, taken[0].payload)
        assertContentEquals(second, taken[1].payload)
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
        assertContentEquals(first, taken[0].payload)
        assertContentEquals(second, taken[1].payload)
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

    @Test
    fun trackedLinksAreForgottenWhenAllTheirPeersAreTaken() {
        val pending = store(peersPerLink = 2)
        assertTrue(pending.offer("a", byteArrayOf(1), 0, "first"))
        assertTrue(pending.offer("b", byteArrayOf(2), 0, "second"))
        pending.take("a", 0)
        pending.take("b", 0)
        assertEquals(0, pending.trackedLinkCount)
    }

    @Test
    fun payloadsUnderOneIdOnTwoLinksAreTwoPlacesThatDoNotProlongEachOther() {
        // The sequence a reviewer found: places on link "b" are full; payloads under the same ids
        // then arrive over link "a". When the first ones age out, "b" must have room again.
        val pending = PendingEncryptedPayloads(maxPeersPerLink = 2, maxAgeMs = 30)
        assertTrue(pending.offer("first", byteArrayOf(1), 0, "b"))
        assertTrue(pending.offer("second", byteArrayOf(2), 0, "b"))
        assertFalse(pending.offer("newcomer", byteArrayOf(9), 1, "b"))

        assertTrue(pending.offer("first", byteArrayOf(3), 25, "a"))
        assertTrue(pending.offer("second", byteArrayOf(4), 25, "a"))
        assertEquals(4, pending.count)

        // At 31 the payloads kept on "b" are gone, and so are their places there.
        assertTrue(pending.offer("newcomer", byteArrayOf(9), 31, "b"))
        assertEquals(3, pending.count)
    }

    @Test
    fun aFullLinkDoesNotStopTheSameIdFromBeingKeptOnAnother() {
        val pending = PendingEncryptedPayloads(maxPeersPerLink = 1)
        assertTrue(pending.offer("holder", byteArrayOf(1), 0, "b"))
        assertFalse(pending.offer("peer", byteArrayOf(2), 0, "b"))
        assertTrue(pending.offer("peer", byteArrayOf(2), 0, "a"))
    }

    @Test
    fun takeReturnsWhatWasKeptOnEveryLinkInTheOrderItArrived() {
        val pending = PendingEncryptedPayloads()
        pending.offer("peer", byteArrayOf(1), 0, "b")
        pending.offer("peer", byteArrayOf(2), 0, "a")
        pending.offer("peer", byteArrayOf(3), 0, "b")
        pending.offer("other", byteArrayOf(9), 0, "b")

        val taken = pending.take("peer", 1)

        assertEquals(listOf(1, 2, 3), taken.map { it.payload.single().toInt() })
        // Each one says which link it arrived on, so a later failure is charged to the right one.
        assertEquals(listOf("b", "a", "b"), taken.map { it.link })
        assertEquals(1, pending.count)
        assertEquals(1, pending.trackedLinkCount)
    }

    @Test
    fun dropForgetsAnIdOnEveryLink() {
        val pending = PendingEncryptedPayloads()
        pending.offer("peer", byteArrayOf(1), 0, "a")
        pending.offer("peer", byteArrayOf(2), 0, "b")

        pending.drop("peer")

        assertEquals(0, pending.count)
        assertEquals(0, pending.bytes)
        assertEquals(0, pending.trackedLinkCount)
    }
}
