package com.bitchat.bluetooth.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The rules that decide when the embedded node opens an outbound BLE link.
 *
 * These are the rules the device journal shows being broken: overlapping connection attempts that
 * cancelled each other (`le-connection-abort-by-local`), a second link opened to a phone already
 * connected under a rotated address, and attempts that gattlib never reported on piling up until
 * the registry claimed seven links while every broadcast reached nobody.
 */
class CentralLinkPolicyTest {

    private val phoneA = "5D:7E:6C:61:2B:6E"
    private val phoneARotated = "74:6D:62:4B:4E:57"
    private val other = "7F:AC:24:B9:90:01"

    private fun policy() = CentralLinkPolicy()

    @Test
    fun onlyOneConnectionAttemptIsAllowedAtATime() {
        val policy = policy()

        assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(phoneA, now = 0L))

        // The controller has a single initiator: asking it to start a second attempt makes the host
        // cancel the first, which is the le-connection-abort-by-local in the journal.
        val second = policy.onDiscovered(other, now = 10L)
        assertIs<CentralLinkPolicy.Decision.Skip>(second)
        assertTrue(second.reason.contains("in flight"))
        assertEquals(1, policy.pendingCount())
    }

    @Test
    fun theNextAttemptIsAllowedOnceTheFirstSettles() {
        val policy = policy()

        assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(phoneA, now = 0L))
        policy.onConnected(phoneA)

        assertEquals(0, policy.pendingCount())
        assertEquals(1, policy.establishedCount())
        assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(other, now = 100L))
    }

    @Test
    fun aDeviceAlreadyConnectedToUsAsACentralIsNotConnectedBackTo() {
        val policy = policy()

        // BLE permits one link between two devices. The phone has already reached us, so dialling
        // it is at best wasted and at worst what displaces the link that works.
        val decision = policy.onDiscovered(phoneA, now = 0L, inboundAddresses = setOf(phoneA))
        assertIs<CentralLinkPolicy.Decision.Skip>(decision)
        assertTrue(decision.reason.contains("central"))
        assertEquals(0, policy.pendingCount())
    }

    @Test
    fun aPeerAlreadyLinkedUnderAnotherAddressIsNotDialledAgain() {
        val policy = policy()

        assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(phoneA, now = 0L))
        policy.onConnected(phoneA)

        // Android rotates its advertising address, so the same phone is offered again under a MAC
        // nothing but the peer ID relates to the first.
        val decision = policy.onDiscovered(
            address = phoneARotated,
            now = 1_000L,
            peerAddresses = setOf(phoneA, phoneARotated)
        )
        assertIs<CentralLinkPolicy.Decision.Skip>(decision)
        assertTrue(decision.reason.contains("same peer"))
    }

    @Test
    fun aRotatedAddressIsStillDialledWhenThePeerHasNoLiveLink() {
        val policy = policy()

        assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(phoneA, now = 0L))
        policy.onConnected(phoneA)
        policy.onReleased(phoneA, now = 500L)

        assertIs<CentralLinkPolicy.Decision.Connect>(
            policy.onDiscovered(phoneARotated, now = 1_000L, peerAddresses = setOf(phoneA, phoneARotated))
        )
    }

    @Test
    fun theLinkBudgetIsNotExceeded() {
        val policy = CentralLinkPolicy(maxCentralLinks = 2)

        listOf("AA:00:00:00:00:01", "AA:00:00:00:00:02").forEachIndexed { index, address ->
            assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(address, now = index * 100L))
            policy.onConnected(address)
        }

        val decision = policy.onDiscovered("AA:00:00:00:00:03", now = 1_000L)
        assertIs<CentralLinkPolicy.Decision.Skip>(decision)
        assertTrue(decision.reason.contains("outbound link"))

    }

    @Test
    fun anAttemptThatIsNeverReportedOnIsAbandonedAtTheDeadline() {
        val policy = policy()

        assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(phoneA, now = 0L))

        // gattlib's own connect timeout does not fail the attempt: `_stop_connect_func` only clears
        // its timer. Nothing but this deadline ever ends one.
        assertTrue(policy.expiredAttempts(CentralLinkPolicy.CONNECT_TIMEOUT_MS - 1).isEmpty())
        assertEquals(
            listOf(phoneA),
            policy.expiredAttempts(CentralLinkPolicy.CONNECT_TIMEOUT_MS)
        )

        policy.onReleased(phoneA, now = CentralLinkPolicy.CONNECT_TIMEOUT_MS)
        assertEquals(0, policy.pendingCount())
        assertFalse(policy.isPending(phoneA))
    }

    @Test
    fun aSuccessfulConnectIsNeverReapedHoweverLongItTook() {
        // Successful connects in the journal took 8.2s, 13.8s and 22.0s, so the deadline has to
        // clear the slowest of them.
        val policy = policy()
        assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(phoneA, now = 0L))
        assertTrue(policy.expiredAttempts(22_000L).isEmpty())
        policy.onConnected(phoneA)
        assertTrue(policy.expiredAttempts(60_000L).isEmpty())
    }

    @Test
    fun aFailedAddressBacksOffAndTheBackoffDoubles() {
        val policy = policy()

        assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(phoneA, now = 0L))
        val firstFailedAt = 100L
        policy.onReleased(phoneA, now = firstFailedAt)

        assertIs<CentralLinkPolicy.Decision.Skip>(
            policy.onDiscovered(phoneA, now = firstFailedAt + CentralLinkPolicy.BASE_BACKOFF_MS - 1)
        )
        val secondAttemptAt = firstFailedAt + CentralLinkPolicy.BASE_BACKOFF_MS
        assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(phoneA, now = secondAttemptAt))

        val secondFailedAt = secondAttemptAt + 200L
        policy.onReleased(phoneA, now = secondFailedAt)

        assertIs<CentralLinkPolicy.Decision.Skip>(
            policy.onDiscovered(phoneA, now = secondFailedAt + 2 * CentralLinkPolicy.BASE_BACKOFF_MS - 1)
        )
        assertIs<CentralLinkPolicy.Decision.Connect>(
            policy.onDiscovered(phoneA, now = secondFailedAt + 2 * CentralLinkPolicy.BASE_BACKOFF_MS)
        )
    }

    @Test
    fun aSlowAttemptStillOwesItsBackoffAfterItFails() {
        val policy = policy()

        // `Device.Connect()` blocks for the full 25s D-Bus timeout and BlueZ carries on connecting
        // after it returns, so an immediate retry comes back as org.bluez.Error.InProgress. The
        // backoff has to run from when the attempt ended, not from when it started.
        assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(phoneA, now = 0L))
        val failedAt = 25_000L
        policy.onReleased(phoneA, now = failedAt)

        assertIs<CentralLinkPolicy.Decision.Skip>(policy.onDiscovered(phoneA, now = failedAt + 10L))
        assertIs<CentralLinkPolicy.Decision.Connect>(
            policy.onDiscovered(phoneA, now = failedAt + CentralLinkPolicy.BASE_BACKOFF_MS)
        )
    }

    @Test
    fun theBackoffIsCapped() {
        val policy = policy()
        assertEquals(0L, policy.backoffFor(0))
        assertEquals(CentralLinkPolicy.BASE_BACKOFF_MS, policy.backoffFor(1))
        assertEquals(2 * CentralLinkPolicy.BASE_BACKOFF_MS, policy.backoffFor(2))
        assertEquals(CentralLinkPolicy.MAX_BACKOFF_MS, policy.backoffFor(20))
    }

    @Test
    fun aConnectedAddressIsNotDialledAgain() {
        val policy = policy()
        assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(phoneA, now = 0L))
        policy.onConnected(phoneA)

        val decision = policy.onDiscovered(phoneA, now = 60_000L)
        assertIs<CentralLinkPolicy.Decision.Skip>(decision)
        assertTrue(decision.reason.contains("already linked"))
    }

    @Test
    fun aSuccessfulConnectClearsTheAddressBackoff() {
        val policy = policy()

        assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(phoneA, now = 0L))
        policy.onReleased(phoneA, now = 0L)
        assertIs<CentralLinkPolicy.Decision.Connect>(
            policy.onDiscovered(phoneA, now = CentralLinkPolicy.BASE_BACKOFF_MS)
        )
        policy.onConnected(phoneA)
        policy.onReleased(phoneA, now = CentralLinkPolicy.BASE_BACKOFF_MS + 1)

        // The link worked, so its history of failures says nothing about the next attempt.
        assertIs<CentralLinkPolicy.Decision.Connect>(
            policy.onDiscovered(phoneA, now = CentralLinkPolicy.BASE_BACKOFF_MS + 2)
        )
    }
}
