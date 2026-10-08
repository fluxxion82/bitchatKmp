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

    @Test
    fun anAttemptGattlibStillOwnsFreesTheSlotForOtherPeers() {
        val policy = policy()

        // The reaper cannot cancel a native attempt, so a reaped address can come back BUSY. Holding
        // the single connect slot for it would stop the node talking to anyone else.
        assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(phoneA, now = 0L))
        policy.onNativeBusy(phoneA, now = 1_000L)

        assertEquals(0, policy.pendingCount())
        assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(other, now = 1_100L))
    }

    @Test
    fun anAttemptGattlibStillOwnsIsNotRetriedAtTheBaseBackoff() {
        val policy = policy()

        policy.onDiscovered(phoneA, now = 0L)
        policy.onNativeBusy(phoneA, now = 1_000L)

        // Retrying on the ordinary backoff just collects another BUSY: gattlib holds that address
        // until it lets go, and nothing this side can hurry it.
        assertIs<CentralLinkPolicy.Decision.Skip>(
            policy.onDiscovered(phoneA, now = 1_000L + CentralLinkPolicy.BASE_BACKOFF_MS)
        )
        assertIs<CentralLinkPolicy.Decision.Connect>(
            policy.onDiscovered(phoneA, now = 1_000L + CentralLinkPolicy.MAX_BACKOFF_MS)
        )
    }

    @Test
    fun aBusyAddressStopsBeingPenalisedOnceItConnects() {
        val policy = policy()

        policy.onDiscovered(phoneA, now = 0L)
        policy.onNativeBusy(phoneA, now = 1_000L)
        policy.onDiscovered(phoneA, now = 1_000L + CentralLinkPolicy.MAX_BACKOFF_MS)
        policy.onConnected(phoneA)
        policy.onReleased(phoneA, now = 2_000_000L)

        assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(phoneA, now = 2_000_001L))
    }

    @Test
    fun piImmediateDropsUseTheShortLadderThenQuarantineThePeer() {
        val policy = CentralLinkPolicy(immediateDropRetryEnabled = true)

        fun connectThenDrop(attemptAt: Long) {
            assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(phoneA, now = attemptAt))
            policy.onConnected(phoneA, now = attemptAt + 1L)
            policy.onReleased(phoneA, now = attemptAt + 2L)
        }

        connectThenDrop(attemptAt = 0L)
        assertIs<CentralLinkPolicy.Decision.Skip>(policy.onDiscovered(phoneA, now = 5_001L))
        connectThenDrop(attemptAt = 5_002L)
        assertIs<CentralLinkPolicy.Decision.Skip>(policy.onDiscovered(phoneA, now = 15_003L))
        connectThenDrop(attemptAt = 15_004L)
        assertIs<CentralLinkPolicy.Decision.Skip>(policy.onDiscovered(phoneA, now = 35_005L))
        connectThenDrop(attemptAt = 35_006L)

        assertIs<CentralLinkPolicy.Decision.Skip>(policy.onDiscovered(phoneA, now = 155_007L))
        assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(phoneA, now = 155_008L))
    }

    @Test
    fun piImmediateDropRecordResetsAfterAHealthyLinkInterval() {
        val policy = CentralLinkPolicy(immediateDropRetryEnabled = true)

        assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(phoneA, now = 0L))
        policy.onConnected(phoneA, now = 1L)
        policy.onReleased(phoneA, now = CentralLinkPolicy.CONNECT_TIMEOUT_MS + 1L)

        assertIs<CentralLinkPolicy.Decision.Connect>(
            policy.onDiscovered(phoneA, now = CentralLinkPolicy.CONNECT_TIMEOUT_MS + 2L)
        )
    }

    @Test
    fun piImmediateDropRecordResetsWhenAMeshFrameIsExchanged() {
        val policy = CentralLinkPolicy(immediateDropRetryEnabled = true)

        assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(phoneA, now = 0L))
        policy.onConnected(phoneA, now = 1L)
        policy.onReleased(phoneA, now = 2L)
        policy.onMeshFrameExchanged(phoneA)

        assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(phoneA, now = 3L))
    }

    // A link the GATT client no longer holds. Measured on a board on 2026-10-08: the policy kept a
    // phone's old address as established for hours after the client had dropped it, which with the
    // one real link filled the budget, and the board dialled nobody.

    @Test
    fun aLinkTheClientNoLongerHoldsIsReleased() {
        val policy = policy()
        assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(phoneA, now = 0L))
        policy.onConnected(phoneA)

        assertEquals(listOf(phoneA), policy.releaseUnheld(held = emptySet(), now = 1_000L))

        assertEquals(0, policy.establishedCount())
    }

    @Test
    fun aLinkTheClientHoldsIsKept() {
        val policy = policy()
        assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(phoneA, now = 0L))
        policy.onConnected(phoneA)

        assertEquals(emptyList(), policy.releaseUnheld(held = setOf(phoneA), now = 1_000L))

        assertEquals(1, policy.establishedCount())
        val again = policy.onDiscovered(phoneA, now = 2_000L)
        assertIs<CentralLinkPolicy.Decision.Skip>(again)
        assertTrue(again.reason.contains("already linked"))
    }

    @Test
    fun anAttemptInFlightIsNotReleasedForHavingNoLinkYet() {
        val policy = policy()
        assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(phoneA, now = 0L))

        // The client holds nothing for an address until gattlib reports the connection, so an
        // attempt is always "unheld". Releasing it here would free the one initiator mid-attempt.
        assertEquals(emptyList(), policy.releaseUnheld(held = emptySet(), now = 1_000L))

        assertTrue(policy.isPending(phoneA))
        assertEquals(1, policy.pendingCount())
    }

    @Test
    fun aPhantomLinkNoLongerUsesUpTheLinkBudget() {
        val policy = policy()
        val third = "90:82:8D:69:79:2D"
        assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(phoneA, now = 0L))
        policy.onConnected(phoneA)
        assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(other, now = 10L))
        policy.onConnected(other)

        // What the board logged every five seconds: two links counted, one of them real.
        val refused = policy.onDiscovered(third, now = 20L)
        assertIs<CentralLinkPolicy.Decision.Skip>(refused)
        assertTrue(refused.reason.contains("outbound link(s) already open"))

        assertEquals(listOf(phoneA), policy.releaseUnheld(held = setOf(other), now = 30L))

        assertEquals(1, policy.establishedCount())
        assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(third, now = 40L))
    }

    @Test
    fun aLinkReportedReadyAfterItsLossCountsAsALinkThatDroppedAtOnce() {
        val policy = CentralLinkPolicy(immediateDropRetryEnabled = true)
        assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(phoneA, now = 0L))

        // The two reports are handled by separately launched coroutines, so the loss can be handled
        // first, when there is nothing to release yet, and the ready after it.
        policy.onReleased(phoneA, now = 10L)
        policy.onConnected(phoneA, now = 11L)
        assertEquals(listOf(phoneA), policy.releaseUnheld(held = emptySet(), now = 11L))

        // The same outcome as the two handled in order: not linked, and on the first rung of the
        // immediate-drop ladder.
        assertEquals(0, policy.establishedCount())
        val soon = policy.onDiscovered(phoneA, now = 12L)
        assertIs<CentralLinkPolicy.Decision.Skip>(soon)
        assertTrue(soon.reason.contains("immediate-drop"))
        assertIs<CentralLinkPolicy.Decision.Connect>(
            policy.onDiscovered(phoneA, now = 11L + CentralLinkPolicy.FIRST_IMMEDIATE_DROP_BACKOFF_MS)
        )
    }

    @Test
    fun aLossReportedAfterItsLinkWasAlreadyReleasedIsNotASecondDrop() {
        val policy = CentralLinkPolicy(immediateDropRetryEnabled = true)
        assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(phoneA, now = 0L))
        policy.onConnected(phoneA, now = 1L)
        assertEquals(listOf(phoneA), policy.releaseUnheld(held = emptySet(), now = 2L))

        // The sweep got there first; the loss it anticipated arrives later.
        policy.onReleased(phoneA, now = 4_000L)

        // Still one drop, counted from when the link was released.
        assertIs<CentralLinkPolicy.Decision.Connect>(
            policy.onDiscovered(phoneA, now = 2L + CentralLinkPolicy.FIRST_IMMEDIATE_DROP_BACKOFF_MS)
        )
    }
}
