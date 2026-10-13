package com.bitchat.bluetooth.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
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
    fun theEmbeddedBackoffDoublesUntilItReachesTenMinutes() {
        val policy = CentralLinkPolicy(maxBackoffMs = CentralLinkPolicy.EMBEDDED_MAX_BACKOFF_MS)

        assertEquals(5_000L, policy.backoffFor(1))
        assertEquals(10_000L, policy.backoffFor(2))
        assertEquals(320_000L, policy.backoffFor(7))
        assertEquals(600_000L, policy.backoffFor(8))
        assertEquals(600_000L, policy.backoffFor(20))

        var attemptAt = 0L
        repeat(8) { attempt ->
            val delay = policy.backoffFor(attempt + 1)
            assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(phoneA, now = attemptAt))
            policy.onReleased(phoneA, now = attemptAt)
            assertEquals(
                "within backoff",
                assertIs<CentralLinkPolicy.Decision.Skip>(
                    policy.onDiscovered(phoneA, now = attemptAt + delay - 1L)
                ).reason
            )
            attemptAt += delay
        }
        assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(phoneA, now = attemptAt))
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
    fun anAttemptGattlibStillOwnsCostsAboutAMinuteHoweverFarTheBackoffCanGrow() {
        val policy = CentralLinkPolicy(maxBackoffMs = CentralLinkPolicy.EMBEDDED_MAX_BACKOFF_MS)

        policy.onDiscovered(phoneA, now = 0L)
        policy.onNativeBusy(phoneA, now = 1_000L)

        // The first step of the ladder that is a minute or more: 80 s. Not the ten minutes that are
        // for a peer that never answers; gattlib holding a dead attempt says nothing about the peer.
        assertIs<CentralLinkPolicy.Decision.Skip>(policy.onDiscovered(phoneA, now = 1_000L + 80_000L - 1L))
        assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(phoneA, now = 1_000L + 80_000L))
    }

    @Test
    fun anAttemptGattlibStillOwnsDoesNotShortenALongerBackoff() {
        val policy = CentralLinkPolicy(maxBackoffMs = CentralLinkPolicy.EMBEDDED_MAX_BACKOFF_MS)

        // Eight dials that failed: the address is at the ceiling.
        var now = 0L
        repeat(8) { attempt ->
            assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(phoneA, now = now))
            policy.onReleased(phoneA, now = now)
            now += policy.backoffFor(attempt + 1)
        }
        assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(phoneA, now = now))
        policy.onNativeBusy(phoneA, now = now)

        val ceiling = CentralLinkPolicy.EMBEDDED_MAX_BACKOFF_MS
        assertIs<CentralLinkPolicy.Decision.Skip>(policy.onDiscovered(phoneA, now = now + 80_000L))
        assertIs<CentralLinkPolicy.Decision.Skip>(policy.onDiscovered(phoneA, now = now + ceiling - 1L))
        assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(phoneA, now = now + ceiling))
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

    // A limit on dialling as a whole. A dial switches the board's advertising and scanning off for
    // its length, and no rule about one address bounds what many addresses cost together. How much
    // must be left for a dial depends on the address's standing: one with redials left from a link
    // that lasted needs anything at all, one never tried more than 30 s, one that is failing more
    // than 60 s.

    private val keptForOthers = "dial time kept for peers in better standing"

    private fun budgeted(maxConcurrentConnects: Int = CentralLinkPolicy.MAX_CONCURRENT_CONNECTS) =
        CentralLinkPolicy(
            maxConcurrentConnects = maxConcurrentConnects,
            maxBackoffMs = CentralLinkPolicy.EMBEDDED_MAX_BACKOFF_MS,
            dialBudget = DialBudget()
        )

    private fun fresh(n: Int) = "AA:00:00:00:00:" + n.toString().padStart(2, '0')

    private fun CentralLinkPolicy.refusal(address: String, now: Long): String =
        assertIs<CentralLinkPolicy.Decision.Skip>(onDiscovered(address, now)).reason

    /**
     * A dial to [address] comes up at once at [from], and the link lasts just as long as a link has
     * to for it to count. No dial time is used. Returns the time the link ends.
     */
    private fun linkThatLasts(policy: CentralLinkPolicy, address: String, from: Long = 0L): Long {
        assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(address, from))
        policy.onConnected(address, from)
        policy.onReleased(address, from + CentralLinkPolicy.CONNECT_TIMEOUT_MS)
        return from + CentralLinkPolicy.CONNECT_TIMEOUT_MS
    }

    /**
     * [by], never tried before, holds an attempt for [seconds], starting at [from] with a full
     * allowance. Returns the time at which it gives up.
     */
    private fun dialFor(policy: CentralLinkPolicy, seconds: Long, from: Long = 0L, by: String = fresh(98)): Long {
        assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(by, from))
        policy.onReleased(by, from + seconds * 1_000L)
        return from + seconds * 1_000L
    }

    /**
     * [address], which has not failed since it last linked, is dialled [dials] times from [from]
     * on, each as soon as its backoff allows and each failing at once, so that no dial time is
     * used. Returns the time of the last failure.
     */
    private fun failInARow(policy: CentralLinkPolicy, address: String, dials: Int, from: Long): Long {
        var now = from
        repeat(dials) { failedSoFar ->
            now += policy.backoffFor(failedSoFar)
            assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(address, now))
            policy.onReleased(address, now)
        }
        return now
    }

    /** As many links that last, one after another from time 0, as addresses can have redials. */
    private fun fillTheRedialMemory(policy: CentralLinkPolicy): Long {
        var now = 0L
        (0 until CentralLinkPolicy.MAX_REDIAL_ADDRESSES).forEach { now = linkThatLasts(policy, fresh(it), from = now) }
        return now
    }

    @Test
    fun withABudgetDiallingStopsWhenDialTimeIsUsedUpAndStartsAgainWhenSomeIsBack() {
        val policy = budgeted()
        val linkEnded = linkThatLasts(policy, phoneA)
        // 90 s, less 120 s, plus the 20 s regained meanwhile: 10 s overdrawn.
        val now = dialFor(policy, seconds = 120L, from = linkEnded)
        assertEquals(-10_000L, policy.dialTimeLeftMs(now))

        assertEquals("dial time used up", policy.refusal(phoneA, now))
        assertEquals(0, policy.pendingCount())

        // Ten seconds of debt take a minute to pay back, and even a peer with redials left needs
        // something left.
        assertEquals("dial time used up", policy.refusal(phoneA, now + 60_000L))
        assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(phoneA, now + 60_001L))
    }

    @Test
    fun aRefusalForWantOfDialTimeLeavesTheAddressAsItWas() {
        val policy = budgeted()
        val linkEnded = linkThatLasts(policy, phoneA)
        val now = dialFor(policy, seconds = 120L, from = linkEnded)

        repeat(5) { sweep -> policy.refusal(phoneA, now + sweep * 5_000L) }
        val grantedAt = now + 60_001L
        assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(phoneA, grantedAt))
        policy.onReleased(phoneA, grantedAt)

        // The first step of its backoff and no more: the refusals were not attempts. Nor did they
        // use up its redials: five would have been all of them, and it still goes with next to
        // nothing left.
        assertEquals("within backoff", policy.refusal(phoneA, grantedAt + CentralLinkPolicy.BASE_BACKOFF_MS - 1L))
        assertIs<CentralLinkPolicy.Decision.Connect>(
            policy.onDiscovered(phoneA, grantedAt + CentralLinkPolicy.BASE_BACKOFF_MS)
        )
    }

    @Test
    fun anAddressNeverTriedWaitsWhileOneWithRedialsLeftStillGoes() {
        val policy = budgeted()
        val linkEnded = linkThatLasts(policy, other)
        // 90 - 72 + 12: 30 s exactly.
        val now = dialFor(policy, seconds = 72L, from = linkEnded)
        assertEquals(DialBudget.UNTRIED_RESERVE_MS, policy.dialTimeLeftMs(now))

        assertEquals(keptForOthers, policy.refusal(fresh(3), now))
        assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(other, now))
    }

    @Test
    fun anAddressNeverTriedGoesOnceThereIsMoreThanIsKeptForRedials() {
        val policy = budgeted()
        val now = dialFor(policy, seconds = 72L)
        assertEquals(keptForOthers, policy.refusal(fresh(3), now))

        val grantedAt = now + 1L
        assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(fresh(3), grantedAt))
        policy.onReleased(fresh(3), grantedAt)

        // It has failed once now. When the first step of its backoff is over, what holds it back
        // is that it is failing: the refusal a millisecond before the dial counted nothing.
        assertEquals("within backoff", policy.refusal(fresh(3), grantedAt + CentralLinkPolicy.BASE_BACKOFF_MS - 1L))
        assertEquals(keptForOthers, policy.refusal(fresh(3), grantedAt + CentralLinkPolicy.BASE_BACKOFF_MS))
    }

    @Test
    fun anAddressThatIsFailingWaitsWhileOneNeverTriedStillGoes() {
        val policy = budgeted()
        assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(fresh(1), now = 0L))
        policy.onReleased(fresh(1), now = 0L)
        // 90 - 36 + 6: 60 s exactly.
        val now = dialFor(policy, seconds = 36L)
        assertEquals(DialBudget.FAILING_RESERVE_MS, policy.dialTimeLeftMs(now))

        assertEquals(keptForOthers, policy.refusal(fresh(1), now))
        assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(fresh(3), now))
    }

    @Test
    fun anAddressThatIsFailingGoesOnceThereIsMoreThanIsKeptForFirstTries() {
        val policy = budgeted()
        assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(fresh(1), now = 0L))
        policy.onReleased(fresh(1), now = 0L)
        val now = dialFor(policy, seconds = 36L)
        assertEquals(keptForOthers, policy.refusal(fresh(1), now))

        val grantedAt = now + 1L
        assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(fresh(1), grantedAt))
        policy.onReleased(fresh(1), grantedAt)

        // Its second failure, so the second step of its backoff, counted from that failure: the
        // refusal before it neither counted as an attempt nor started a wait.
        val secondStep = 2 * CentralLinkPolicy.BASE_BACKOFF_MS
        assertEquals("within backoff", policy.refusal(fresh(1), grantedAt + secondStep - 1L))
        assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(fresh(1), grantedAt + secondStep))
    }

    @Test
    fun aLinkThatLastedGivesItsAddressFiveDialsForWhichAnythingLeftIsEnough() {
        val policy = budgeted()
        val linkEnded = linkThatLasts(policy, phoneA)
        val failedAt = failInARow(policy, phoneA, dials = 4, from = linkEnded)
        // 90 - 84 + 14: 20 s, below what is kept back from every other standing. The 40 s its
        // fourth failure costs phoneA are over by then.
        val now = dialFor(policy, seconds = 84L, from = failedAt)
        assertEquals(20_000L, policy.dialTimeLeftMs(now))
        assertEquals(keptForOthers, policy.refusal(fresh(3), now))

        assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(phoneA, now))

        // That was the fifth. When it has failed too and its backoff is over there are 33 s again:
        // enough for an address never tried, not for one that is failing, and that is what it is.
        policy.onReleased(phoneA, now)
        val later = now + policy.backoffFor(5)
        assertEquals(33_333L, policy.dialTimeLeftMs(later))
        assertEquals(keptForOthers, policy.refusal(phoneA, later))
    }

    @Test
    fun aLinkThatDidNotLastGivesNothing() {
        val policy = budgeted()
        assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(phoneA, now = 0L))
        policy.onConnected(phoneA, now = 0L)
        val lost = CentralLinkPolicy.CONNECT_TIMEOUT_MS - 1L
        policy.onReleased(phoneA, now = lost)

        val now = dialFor(policy, seconds = 84L, from = lost)
        assertEquals(keptForOthers, policy.refusal(phoneA, now))
    }

    @Test
    fun whenARedialLinksAndThatLinkLastsThereAreFiveAgain() {
        val policy = budgeted()
        val linkEnded = linkThatLasts(policy, phoneA)
        val failedAt = failInARow(policy, phoneA, dials = 4, from = linkEnded)
        // The fifth and last comes up, and that link lasts too.
        val relinked = linkThatLasts(policy, phoneA, from = failedAt + policy.backoffFor(4))

        val failedAgainAt = failInARow(policy, phoneA, dials = 4, from = relinked)
        val now = dialFor(policy, seconds = 84L, from = failedAgainAt)
        assertEquals(20_000L, policy.dialTimeLeftMs(now))
        assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(phoneA, now))
    }

    @Test
    fun onlyTheAddressesWhoseLinksEndedLastHaveRedials() {
        val policy = budgeted()
        val filledAt = fillTheRedialMemory(policy)
        // One more than there is room for: the first is the one that loses what it had.
        val linkEnded = linkThatLasts(policy, fresh(CentralLinkPolicy.MAX_REDIAL_ADDRESSES), from = filledAt)
        val now = dialFor(policy, seconds = 84L, from = linkEnded)
        assertEquals(20_000L, policy.dialTimeLeftMs(now))

        assertEquals(keptForOthers, policy.refusal(fresh(0), now))
        assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(fresh(1), now))
    }

    @Test
    fun aLinkThatLastsAgainPutsItsAddressLastInLineToLoseItsRedials() {
        val policy = budgeted()
        val filledAt = fillTheRedialMemory(policy)
        val again = linkThatLasts(policy, fresh(0), from = filledAt)
        val linkEnded = linkThatLasts(policy, fresh(CentralLinkPolicy.MAX_REDIAL_ADDRESSES), from = again)
        val now = dialFor(policy, seconds = 84L, from = linkEnded)

        assertEquals(keptForOthers, policy.refusal(fresh(1), now))
        assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(fresh(0), now))
    }

    @Test
    fun usingARedialDoesNotMoveAnAddressBackInLineAndLosingTheRestLeavesItFailing() {
        val policy = budgeted()
        val filledAt = fillTheRedialMemory(policy)
        assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(fresh(0), filledAt))
        policy.onReleased(fresh(0), filledAt)

        // Only a link that lasted renews an address's place. The next such link pushes out the
        // first, whatever it was dialled since.
        val linkEnded = linkThatLasts(policy, fresh(CentralLinkPolicy.MAX_REDIAL_ADDRESSES), from = filledAt)

        // 90 - 54 + 9: 45 s. Enough for an address never tried; the first has failed a dial since
        // it linked, and losing its redials did not wipe that out.
        val now = dialFor(policy, seconds = 54L, from = linkEnded)
        assertEquals(45_000L, policy.dialTimeLeftMs(now))
        assertEquals(keptForOthers, policy.refusal(fresh(0), now))
        assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(fresh(1), now))
    }

    @Test
    fun aRefusalForWantOfDialTimeDoesNotMoveAnAddressBackInLine() {
        val policy = budgeted()
        val filledAt = fillTheRedialMemory(policy)
        val overdrawnAt = dialFor(policy, seconds = 120L, from = filledAt)
        assertEquals("dial time used up", policy.refusal(fresh(0), overdrawnAt))

        // An hour later the allowance is full again, and another link that lasts still pushes out
        // the first.
        val later = overdrawnAt + 3_600_000L
        val linkEnded = linkThatLasts(policy, fresh(CentralLinkPolicy.MAX_REDIAL_ADDRESSES), from = later)
        val now = dialFor(policy, seconds = 84L, from = linkEnded, by = fresh(97))

        assertEquals(keptForOthers, policy.refusal(fresh(0), now))
        assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(fresh(1), now))
    }

    @Test
    fun aLossReportedWithNoLinkBehindItRenewsNothing() {
        val policy = budgeted()
        val filledAt = fillTheRedialMemory(policy)
        // The first address's link is reported lost a second time, long after it ended.
        policy.onReleased(fresh(0), filledAt)

        val linkEnded = linkThatLasts(policy, fresh(CentralLinkPolicy.MAX_REDIAL_ADDRESSES), from = filledAt)
        val now = dialFor(policy, seconds = 84L, from = linkEnded)

        assertEquals(keptForOthers, policy.refusal(fresh(0), now))
        assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(fresh(1), now))
    }

    private fun budgetedAsOnTheBoards() = CentralLinkPolicy(
        maxBackoffMs = CentralLinkPolicy.EMBEDDED_MAX_BACKOFF_MS,
        immediateDropRetryEnabled = true,
        dialBudget = DialBudget()
    )

    @Test
    fun aFrameFromAnAddressGivesItNothing() {
        val policy = budgetedAsOnTheBoards()

        // Frames arrive from centrals that connected in as well; nothing was dialled for them.
        policy.onMeshFrameExchanged(other)

        val now = dialFor(policy, seconds = 84L)
        assertEquals(keptForOthers, policy.refusal(other, now))
    }

    @Test
    fun aFrameDoesNotMakeUpForRedialsThatAreUsedUp() {
        val policy = budgetedAsOnTheBoards()
        val linkEnded = linkThatLasts(policy, phoneA)
        val failedAt = failInARow(policy, phoneA, dials = 5, from = linkEnded)

        policy.onMeshFrameExchanged(phoneA)

        // 90 - 54 + 9 is 45 s; when the 80 s its fifth failure costs phoneA are over there are 49:
        // enough for an address never tried, and it is not one.
        dialFor(policy, seconds = 54L, from = failedAt)
        val now = failedAt + policy.backoffFor(5)
        assertEquals(49_333L, policy.dialTimeLeftMs(now))
        assertEquals(keptForOthers, policy.refusal(phoneA, now))
    }

    /**
     * What can happen to an address short of a link this node dialled lasting: each takes the
     * policy, the address and the time, says how many redials it uses, and returns when it is over.
     */
    private class Happening(val name: String, val redialsUsed: Int, val to: (CentralLinkPolicy, String, Long) -> Long)

    private val shortOfALinkThatLasted = listOf(
        Happening("a dial that fails", redialsUsed = 1) { policy, address, now ->
            assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(address, now))
            policy.onReleased(address, now)
            now
        },
        Happening("a dial gattlib answers BUSY to", redialsUsed = 1) { policy, address, now ->
            assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(address, now))
            policy.onNativeBusy(address, now)
            now
        },
        Happening("a link that is lost a millisecond too soon", redialsUsed = 1) { policy, address, now ->
            assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(address, now))
            policy.onConnected(address, now)
            policy.onReleased(address, now + CentralLinkPolicy.CONNECT_TIMEOUT_MS - 1L)
            now + CentralLinkPolicy.CONNECT_TIMEOUT_MS - 1L
        },
        Happening("a frame", redialsUsed = 0) { policy, address, now ->
            policy.onMeshFrameExchanged(address)
            now
        },
        Happening("a loss reported with no link behind it", redialsUsed = 0) { policy, address, now ->
            policy.onReleased(address, now)
            now
        }
    )

    // Longer than any wait an address can owe: its backoff at the ceiling, the immediate-drop pause.
    private val longAfter = CentralLinkPolicy.EMBEDDED_MAX_BACKOFF_MS

    @Test
    fun nothingShortOfALinkThatLastedGivesAnAddressRedials() {
        shortOfALinkThatLasted.forEach { happening ->
            val policy = budgetedAsOnTheBoards()
            val over = happening.to(policy, phoneA, 0L)

            // With 20 s left only an address with redials is dialled.
            val now = dialFor(policy, seconds = 84L, from = over + longAfter)
            assertEquals(keptForOthers, policy.refusal(phoneA, now), happening.name)
        }
    }

    @Test
    fun nothingShortOfALinkThatLastedGivesBackRedialsThatWereUsed() {
        shortOfALinkThatLasted.forEach { happening ->
            val policy = budgetedAsOnTheBoards()
            val linkEnded = linkThatLasts(policy, phoneA)
            // One of its five is used before anything happens, so that there is something to give back.
            assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(phoneA, linkEnded))
            policy.onReleased(phoneA, linkEnded)
            var now = happening.to(policy, phoneA, linkEnded + longAfter)

            // What should be left is used up, each dial failing at once with a full allowance.
            repeat(CentralLinkPolicy.REDIALS_AFTER_A_LINK - 1 - happening.redialsUsed) {
                now += longAfter
                assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(phoneA, now), happening.name)
                policy.onReleased(phoneA, now)
            }

            val later = dialFor(policy, seconds = 84L, from = now + longAfter)
            assertEquals(keptForOthers, policy.refusal(phoneA, later), happening.name)
        }
    }

    @Test
    fun nothingShortOfALinkThatLastedMovesAnAddressBackInLine() {
        shortOfALinkThatLasted.forEach { happening ->
            val policy = budgetedAsOnTheBoards()
            val filledAt = fillTheRedialMemory(policy)
            val over = happening.to(policy, fresh(0), filledAt)

            // The first address is still the one that loses its redials to a ninth.
            val linkEnded = linkThatLasts(
                policy, fresh(CentralLinkPolicy.MAX_REDIAL_ADDRESSES), from = over + longAfter
            )
            val now = dialFor(policy, seconds = 84L, from = linkEnded)
            assertEquals(keptForOthers, policy.refusal(fresh(0), now), happening.name)
            assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(fresh(1), now), happening.name)
        }
    }

    @Test
    fun aLinkThatLastedGivesRedialsAlsoWhenItIsTheSweepThatFindsItGone() {
        val policy = budgeted()
        assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(phoneA, now = 0L))
        policy.onConnected(phoneA, now = 0L)

        val gone = CentralLinkPolicy.CONNECT_TIMEOUT_MS
        assertEquals(listOf(phoneA), policy.releaseUnheld(held = emptySet(), now = gone))

        val now = dialFor(policy, seconds = 84L, from = gone)
        assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(phoneA, now))
    }

    @Test
    fun aLinkThatWasUpAtAClearGivesNoRedialsWhenItsLossIsReportedLater() {
        val policy = budgeted()
        assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(phoneA, now = 0L))
        policy.onConnected(phoneA, now = 0L)

        policy.clear()
        policy.onReleased(phoneA, now = CentralLinkPolicy.CONNECT_TIMEOUT_MS)

        val now = dialFor(policy, seconds = 84L, from = CentralLinkPolicy.CONNECT_TIMEOUT_MS)
        assertEquals(keptForOthers, policy.refusal(phoneA, now))
    }

    @Test
    fun aClearEndsTheAttemptInFlightAndWithItTheCharge() {
        val policy = budgeted()
        assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(phoneA, now = 0L))
        assertEquals(85_000L, policy.dialTimeLeftMs(6_000L))

        policy.clear()

        assertEquals(0, policy.pendingCount())
        assertEquals(86_000L, policy.dialTimeLeftMs(12_000L))
    }

    @Test
    fun afterAClearNoAddressHasRedialsLeft() {
        val policy = budgeted()
        val linkEnded = linkThatLasts(policy, phoneA)
        val now = dialFor(policy, seconds = 84L, from = linkEnded)

        policy.clear()

        // The dial time used is not given back by it either.
        assertEquals(keptForOthers, policy.refusal(phoneA, now))
    }

    @Test
    fun theSettingsForRedials() {
        assertEquals(5, CentralLinkPolicy.REDIALS_AFTER_A_LINK)
        assertEquals(8, CentralLinkPolicy.MAX_REDIAL_ADDRESSES)
    }

    @Test
    fun anAttemptIsChargedWhileItLastsAndALinkThatIsUpCostsNothing() {
        val policy = budgeted()
        assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(phoneA, now = 0L))

        // Six seconds in: six gone, one back.
        assertEquals(85_000L, policy.dialTimeLeftMs(6_000L))
        policy.onConnected(phoneA, now = 12_000L)
        assertEquals(80_000L, policy.dialTimeLeftMs(12_000L))

        assertEquals(81_000L, policy.dialTimeLeftMs(18_000L))
        assertEquals(DialBudget.CAPACITY_MS, policy.dialTimeLeftMs(3_600_000L))
    }

    @Test
    fun anAttemptThatFailsStopsCostingDialTime() {
        val policy = budgeted()
        assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(phoneA, now = 0L))

        policy.onReleased(phoneA, now = 12_000L)

        assertEquals(80_000L, policy.dialTimeLeftMs(12_000L))
        assertEquals(81_000L, policy.dialTimeLeftMs(18_000L))
    }

    @Test
    fun anAttemptGattlibStillOwnsStopsCostingDialTime() {
        val policy = budgeted()
        assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(phoneA, now = 0L))

        policy.onNativeBusy(phoneA, now = 12_000L)

        assertEquals(80_000L, policy.dialTimeLeftMs(12_000L))
        assertEquals(81_000L, policy.dialTimeLeftMs(18_000L))
    }

    @Test
    fun whileAnAttemptIsInFlightItsTimeCountsAgainstTheNextOne() {
        // Only where two attempts may run at once can a dial be asked for while another is in
        // flight; nothing but that request has read the clock for two minutes.
        val policy = budgeted(maxConcurrentConnects = 2)
        assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(phoneA, now = 0L))

        assertEquals("dial time used up", policy.refusal(other, now = 120_000L))
    }

    @Test
    fun twoAttemptsAtOnceAreChargedForTheTimeEitherIsInFlight() {
        val policy = budgeted(maxConcurrentConnects = 2)
        assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(phoneA, now = 0L))
        assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(other, now = 0L))

        // Six seconds with two in flight cost six, not twelve and not nothing; six more with one
        // left cost six again.
        policy.onReleased(phoneA, now = 6_000L)
        assertEquals(85_000L, policy.dialTimeLeftMs(6_000L))
        policy.onReleased(other, now = 12_000L)
        assertEquals(80_000L, policy.dialTimeLeftMs(12_000L))
    }

    @Test
    fun anAddressInsideItsBackoffIsRefusedForThatWhateverDialTimeIsLeft() {
        val policy = budgeted()
        val now = dialFor(policy, seconds = 120L)
        assertEquals(-10_000L, policy.dialTimeLeftMs(now))

        // It failed a second ago. Dial time is the last thing asked about: while an address's own
        // record refuses it, that is the reason the journal gives.
        assertEquals("within backoff", policy.refusal(fresh(98), now + 1_000L))
    }

    @Test
    fun withABudgetTheTimeALinkCameUpMustBeGiven() {
        val policy = budgeted()
        assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(phoneA, now = 0L))

        assertFailsWith<IllegalArgumentException> { policy.onConnected(phoneA) }
    }

    @Test
    fun withoutABudgetNoDialIsHeldBackForDialTime() {
        val policy = CentralLinkPolicy(maxBackoffMs = CentralLinkPolicy.EMBEDDED_MAX_BACKOFF_MS)

        var now = 0L
        repeat(8) { n ->
            assertIs<CentralLinkPolicy.Decision.Connect>(policy.onDiscovered(fresh(n), now))
            now += 30_000L
            policy.onReleased(fresh(n), now)
        }
        assertNull(policy.dialTimeLeftMs(now))
    }
}
