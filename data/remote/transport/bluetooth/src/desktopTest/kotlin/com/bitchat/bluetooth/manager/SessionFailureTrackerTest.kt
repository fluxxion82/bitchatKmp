package com.bitchat.bluetooth.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SessionFailureTrackerTest {

    private val phone = "3b146c2741c9a37e"
    private val other = "269e37bb6be7caf9"

    @Test
    fun aRunOfFailuresCondemnsTheSession() {
        val tracker = SessionFailureTracker()
        assertNull(tracker.onDecryptFailed(phone, now = 0L))
        assertNull(tracker.onDecryptFailed(phone, now = 100L))
        assertEquals(SessionFailureTracker.RecoveryReason.UNUSABLE, tracker.onDecryptFailed(phone, now = 200L))
    }

    @Test
    fun aSingleBadPacketAmongGoodOnesIsNotTheSessionsFault() {
        val tracker = SessionFailureTracker()
        repeat(10) { i ->
            assertNull(tracker.onDecryptFailed(phone, now = i * 100L))
            tracker.onDecryptSucceeded(phone)
        }
        assertEquals(0, tracker.consecutiveFailures(phone))
    }

    @Test
    fun peersAreCountedSeparately() {
        val tracker = SessionFailureTracker()
        assertNull(tracker.onDecryptFailed(phone, now = 0L))
        assertNull(tracker.onDecryptFailed(other, now = 1L))
        assertNull(tracker.onDecryptFailed(phone, now = 2L))
        assertEquals(SessionFailureTracker.RecoveryReason.UNUSABLE, tracker.onDecryptFailed(phone, now = 3L))
        assertEquals(1, tracker.consecutiveFailures(other))
    }

    @Test
    fun aPeerCannotForceHandshakesFasterThanTheInterval() {
        val tracker = SessionFailureTracker()
        repeat(3) { tracker.onDecryptFailed(phone, now = 0L) }
        repeat(30) { i ->
            assertNull(tracker.onDecryptFailed(phone, now = 1_000L + i.toLong()))
        }
        assertEquals(SessionFailureTracker.RecoveryReason.UNUSABLE, tracker.onDecryptFailed(phone, now = SessionFailureTracker.MIN_RECOVERY_INTERVAL_MS + 1))
    }

    @Test
    fun aRunThatIncludesAFallbackOnlyDecryptSaysTheSessionIsNotShared() {
        val tracker = SessionFailureTracker()
        val fallback = SessionFailureTracker.FailureEvidence.FALLBACK_SUCCESS
        assertNull(tracker.onDecryptFailed(phone, now = 0L))
        assertNull(tracker.onDecryptFailed(phone, now = 1L, evidence = fallback))
        assertEquals(SessionFailureTracker.RecoveryReason.NOT_SHARED, tracker.onDecryptFailed(phone, now = 2L))
    }

    @Test
    fun aDecryptViaTheEstablishedSessionEndsARun() {
        val tracker = SessionFailureTracker()
        val fallback = SessionFailureTracker.FailureEvidence.FALLBACK_SUCCESS
        tracker.onDecryptFailed(phone, now = 0L, evidence = fallback)
        tracker.onDecryptFailed(phone, now = 1L)
        tracker.onDecryptSucceeded(phone)
        assertEquals(0, tracker.consecutiveFailures(phone))
        assertNull(tracker.onDecryptFailed(phone, now = 2L))
        assertNull(tracker.onDecryptFailed(phone, now = 3L))
        assertEquals(SessionFailureTracker.RecoveryReason.UNUSABLE, tracker.onDecryptFailed(phone, now = 4L))
    }

    @Test
    fun forgettingAPeerClearsBothItsCountAndItsRateLimit() {
        val tracker = SessionFailureTracker()
        repeat(3) { tracker.onDecryptFailed(phone, now = 0L) }
        tracker.forget(phone)
        assertEquals(0, tracker.consecutiveFailures(phone))
        assertNull(tracker.onDecryptFailed(phone, now = 10L))
        assertNull(tracker.onDecryptFailed(phone, now = 20L))
        assertEquals(SessionFailureTracker.RecoveryReason.UNUSABLE, tracker.onDecryptFailed(phone, now = 30L))
    }

    @Test
    fun aPeerWithoutASessionIsRecoveredOnItsFirstFailureWithoutARun() {
        val tracker = SessionFailureTracker()
        assertEquals(SessionFailureTracker.RecoveryReason.UNUSABLE, tracker.onDecryptFailed(phone, 0, hasSession = false))
        assertEquals(0, tracker.failureRunCount)
        assertEquals(0, tracker.consecutiveFailures(phone))
    }

    @Test
    fun aPeerWithoutASessionWaitsForItsIntervalBeforeAnotherRecovery() {
        val tracker = SessionFailureTracker()
        assertEquals(SessionFailureTracker.RecoveryReason.UNUSABLE, tracker.onDecryptFailed(phone, 0, hasSession = false))
        assertNull(tracker.onDecryptFailed(phone, 1, hasSession = false))
        assertEquals(SessionFailureTracker.RecoveryReason.UNUSABLE, tracker.onDecryptFailed(phone, SessionFailureTracker.MIN_RECOVERY_INTERVAL_MS, hasSession = false))
    }

    @Test
    fun aNinthPeerWithoutASessionOnOneLinkWaitsWhileAnotherLinkCanRecover() {
        val tracker = SessionFailureTracker(failuresBeforeRecovery = 99, minRecoveryIntervalMs = 100)
        repeat(SessionFailureTracker.MAX_RECOVERIES_PER_INTERVAL_PER_LINK) {
            assertEquals(
                SessionFailureTracker.RecoveryReason.UNUSABLE,
                tracker.onDecryptFailed("no-session-$it", 0, hasSession = false, link = "first")
            )
        }
        assertNull(tracker.onDecryptFailed("waiting", 0, hasSession = false, link = "first"))
        assertEquals(SessionFailureTracker.MAX_RECOVERIES_PER_INTERVAL_PER_LINK, tracker.recoveryCount)
        assertEquals(
            SessionFailureTracker.RecoveryReason.UNUSABLE,
            tracker.onDecryptFailed("other-link", 0, hasSession = false, link = "second")
        )
    }

    @Test
    fun aNinthSessionBackedPeerOnOneLinkWaitsWhileAnotherLinkCanRecover() {
        val tracker = SessionFailureTracker(minRecoveryIntervalMs = 100)
        repeat(SessionFailureTracker.MAX_RECOVERIES_PER_INTERVAL_PER_LINK) {
            assertNull(tracker.onDecryptFailed("session-$it", 0, hasSession = true, link = "first"))
            assertNull(tracker.onDecryptFailed("session-$it", 1, hasSession = true, link = "first"))
            assertEquals(
                SessionFailureTracker.RecoveryReason.UNUSABLE,
                tracker.onDecryptFailed("session-$it", 2, hasSession = true, link = "first")
            )
        }
        assertNull(tracker.onDecryptFailed("waiting", 0, hasSession = true, link = "first"))
        assertNull(tracker.onDecryptFailed("waiting", 1, hasSession = true, link = "first"))
        assertNull(tracker.onDecryptFailed("waiting", 2, hasSession = true, link = "first"))
        assertEquals(SessionFailureTracker.MAX_RECOVERIES_PER_INTERVAL_PER_LINK, tracker.recoveryCount)
        assertNull(tracker.onDecryptFailed("other-link", 0, hasSession = true, link = "second"))
        assertNull(tracker.onDecryptFailed("other-link", 1, hasSession = true, link = "second"))
        assertEquals(
            SessionFailureTracker.RecoveryReason.UNUSABLE,
            tracker.onDecryptFailed("other-link", 2, hasSession = true, link = "second")
        )
    }

    @Test
    fun recoveryCeilingsPruneNoSessionEntries() {
        val tracker = SessionFailureTracker(failuresBeforeRecovery = 99, minRecoveryIntervalMs = 100)
        repeat(SessionFailureTracker.MAX_RECOVERIES_PER_INTERVAL_PER_LINK) {
            assertEquals(
                SessionFailureTracker.RecoveryReason.UNUSABLE,
                tracker.onDecryptFailed("no-session-$it", 0, hasSession = false, link = "first")
            )
        }
        assertNull(tracker.onDecryptFailed("waiting", 0, hasSession = false, link = "first"))
        assertEquals(SessionFailureTracker.RecoveryReason.UNUSABLE, tracker.onDecryptFailed("waiting", 100, hasSession = false, link = "first"))
        assertEquals(1, tracker.recoveryCount)
    }

    @Test
    fun noSessionFloodKeepsNoRunsAndDoesNotTouchASessionBackedCount() {
        val tracker = SessionFailureTracker()
        repeat(2_000) { tracker.onDecryptFailed("invented-$it", it.toLong(), hasSession = false) }
        assertEquals(0, tracker.failureRunCount)
        assertNull(tracker.onDecryptFailed(phone, 3_000, hasSession = true))
        assertNull(tracker.onDecryptFailed(phone, 3_001, hasSession = true))
        assertEquals(2, tracker.consecutiveFailures(phone))
        assertEquals(SessionFailureTracker.RecoveryReason.UNUSABLE, tracker.onDecryptFailed(phone, 3_002, hasSession = true))
    }

    @Test
    fun aSessionRunIsForgottenWhenThePeerNoLongerHasASession() {
        val tracker = SessionFailureTracker()
        assertNull(tracker.onDecryptFailed(phone, 0, hasSession = true))
        assertEquals(SessionFailureTracker.RecoveryReason.UNUSABLE, tracker.onDecryptFailed(phone, 1, hasSession = false))
        assertEquals(0, tracker.failureRunCount)
        assertEquals(0, tracker.consecutiveFailures(phone))
    }

    @Test
    fun aRunDoesNotOutliveItsSessionEvenWhenNoRecoveryIsGranted() {
        val tracker = SessionFailureTracker(minRecoveryIntervalMs = 100)
        // Recovered once without a session, so the peer is inside its interval from here on.
        assertEquals(SessionFailureTracker.RecoveryReason.UNUSABLE, tracker.onDecryptFailed(phone, 0, hasSession = false))
        assertNull(tracker.onDecryptFailed(phone, 1, hasSession = true))
        assertNull(tracker.onDecryptFailed(phone, 2, hasSession = true))
        assertEquals(2, tracker.consecutiveFailures(phone))

        // The session is gone again. Nothing is granted this time, and the old count must go too:
        // otherwise one bad packet on the peer's NEXT session would be taken for the third.
        assertNull(tracker.onDecryptFailed(phone, 3, hasSession = false))
        assertEquals(0, tracker.consecutiveFailures(phone))
        assertEquals(0, tracker.failureRunCount)
    }

    @Test
    fun aNewSessionBackedRunDisplacesTheLeastRecentlyUpdatedOne() {
        val tracker = SessionFailureTracker()
        repeat(SessionFailureTracker.MAX_FAILURE_RUNS) {
            tracker.onDecryptFailed("session-$it", it.toLong(), hasSession = true)
        }
        tracker.onDecryptFailed("session-0", 2_000, hasSession = true)
        tracker.onDecryptFailed("session-new", 2_001, hasSession = true)
        assertEquals(SessionFailureTracker.MAX_FAILURE_RUNS, tracker.failureRunCount)
        assertEquals(2, tracker.consecutiveFailures("session-0"))
        assertEquals(0, tracker.consecutiveFailures("session-1"))
        assertEquals(1, tracker.consecutiveFailures("session-new"))
    }

    @Test
    fun theTotalRecoveryCeilingRefusesTheSixtyFifthAcrossNineLinks() {
        val tracker = SessionFailureTracker(failuresBeforeRecovery = 1, minRecoveryIntervalMs = 100)
        repeat(SessionFailureTracker.MAX_RECOVERIES_PER_INTERVAL_WITH_SESSION) {
            assertEquals(
                SessionFailureTracker.RecoveryReason.UNUSABLE,
                tracker.onDecryptFailed("session-$it", 0, hasSession = true, link = "link-${it / 8}")
            )
        }
        assertNull(tracker.onDecryptFailed("held", 0, hasSession = true, link = "link-8"))
        assertEquals(SessionFailureTracker.MAX_RECOVERIES_PER_INTERVAL_WITH_SESSION, tracker.recoveryCount)
    }

    @Test
    fun aRecoveryRefusedByEitherCeilingDoesNotRecordIt() {
        val perLink = SessionFailureTracker(failuresBeforeRecovery = 1, minRecoveryIntervalMs = 100)
        repeat(SessionFailureTracker.MAX_RECOVERIES_PER_INTERVAL_PER_LINK) {
            perLink.onDecryptFailed("per-link-$it", 0, hasSession = true, link = "first")
        }
        assertNull(perLink.onDecryptFailed("per-link-refused", 0, hasSession = true, link = "first"))
        assertEquals(SessionFailureTracker.MAX_RECOVERIES_PER_INTERVAL_PER_LINK, perLink.recoveryCount)

        val total = SessionFailureTracker(failuresBeforeRecovery = 1, minRecoveryIntervalMs = 100)
        repeat(SessionFailureTracker.MAX_RECOVERIES_PER_INTERVAL_WITH_SESSION) {
            total.onDecryptFailed("total-$it", 0, hasSession = true, link = "link-${it / 8}")
        }
        assertNull(total.onDecryptFailed("total-refused", 0, hasSession = true, link = "link-8"))
        assertEquals(SessionFailureTracker.MAX_RECOVERIES_PER_INTERVAL_WITH_SESSION, total.recoveryCount)
    }

    @Test
    fun aPeerRefusedByThePerLinkCeilingCanRecoverAfterTheInterval() {
        val tracker = SessionFailureTracker(failuresBeforeRecovery = 1, minRecoveryIntervalMs = 100)
        repeat(SessionFailureTracker.MAX_RECOVERIES_PER_INTERVAL_PER_LINK) {
            tracker.onDecryptFailed("session-$it", 0, hasSession = true, link = "first")
        }
        assertNull(tracker.onDecryptFailed("held", 0, hasSession = true, link = "first"))
        assertEquals(1, tracker.consecutiveFailures("held"))
        assertEquals(SessionFailureTracker.RecoveryReason.UNUSABLE, tracker.onDecryptFailed("held", 100, hasSession = true, link = "first"))
        assertEquals(1, tracker.recoveryCount)
    }

    @Test
    fun aRecoveryIsChargedToTheLinkBehindMostOfItsSignalsNotTheLast() {
        // The sequence a reviewer found: two unreadable packets on one link, then a genuine packet
        // read with the old session on another. The third signal must not bill its own link.
        val tracker = SessionFailureTracker()
        assertNull(tracker.onDecryptFailed(phone, 0, hasSession = true, link = "a"))
        assertNull(tracker.onDecryptFailed(phone, 1, hasSession = true, link = "a"))
        assertEquals(
            SessionFailureTracker.RecoveryReason.NOT_SHARED,
            tracker.onDecryptFailed(phone, 2, SessionFailureTracker.FailureEvidence.FALLBACK_SUCCESS, hasSession = true, link = "b")
        )

        assertEquals(1, tracker.recoveriesChargedTo("a"))
        assertEquals(0, tracker.recoveriesChargedTo("b"))
    }

    @Test
    fun whenTheLinkBehindMostSignalsIsAtItsCeilingTheRecoveryWaitsRatherThanBillingAnother() {
        val tracker = SessionFailureTracker(failuresBeforeRecovery = 3, minRecoveryIntervalMs = 100)
        repeat(SessionFailureTracker.MAX_RECOVERIES_PER_INTERVAL_PER_LINK) { peer ->
            repeat(3) { tracker.onDecryptFailed("filler-$peer", it.toLong(), hasSession = true, link = "a") }
        }
        assertEquals(SessionFailureTracker.MAX_RECOVERIES_PER_INTERVAL_PER_LINK, tracker.recoveriesChargedTo("a"))

        tracker.onDecryptFailed(phone, 10, hasSession = true, link = "a")
        tracker.onDecryptFailed(phone, 11, hasSession = true, link = "a")
        assertNull(tracker.onDecryptFailed(phone, 12, hasSession = true, link = "b"))

        assertEquals(0, tracker.recoveriesChargedTo("b"))
        assertEquals(3, tracker.consecutiveFailures(phone))
    }

    @Test
    fun signalsFromThreeLinksAreChargedToNoneOfThem() {
        // One genuine signal on "b", one packet each on two other links: nobody is behind most of
        // them, so no link pays, whichever came first or last.
        val tracker = SessionFailureTracker()
        tracker.onDecryptFailed(phone, 0, hasSession = true, link = "b")
        tracker.onDecryptFailed(phone, 1, hasSession = true, link = "a")
        assertEquals(
            SessionFailureTracker.RecoveryReason.UNUSABLE,
            tracker.onDecryptFailed(phone, 2, hasSession = true, link = "c")
        )

        assertEquals(0, tracker.recoveriesChargedTo("a") + tracker.recoveriesChargedTo("b") + tracker.recoveriesChargedTo("c"))
        assertEquals(1, tracker.recoveriesChargedTo(null))
    }

    @Test
    fun runsNoLinkIsBehindHaveTheirOwnAllowance() {
        val tracker = SessionFailureTracker(failuresBeforeRecovery = 3, minRecoveryIntervalMs = 100)
        fun mixed(peer: String, at: Long) = listOf("a", "b", "c").mapIndexed { index, link ->
            tracker.onDecryptFailed(peer, at + index, hasSession = true, link = link)
        }.last()

        repeat(SessionFailureTracker.MAX_RECOVERIES_PER_INTERVAL_PER_LINK) {
            assertEquals(SessionFailureTracker.RecoveryReason.UNUSABLE, mixed("mixed-$it", 0))
        }
        // The ninth mixed run waits; a run that one link IS behind is not affected by that.
        assertNull(mixed("mixed-ninth", 0))
        repeat(2) { tracker.onDecryptFailed(phone, it.toLong(), hasSession = true, link = "a") }
        assertEquals(
            SessionFailureTracker.RecoveryReason.UNUSABLE,
            tracker.onDecryptFailed(phone, 2, hasSession = true, link = "a")
        )
        assertEquals(1, tracker.recoveriesChargedTo("a"))
        assertEquals(SessionFailureTracker.MAX_RECOVERIES_PER_INTERVAL_PER_LINK, tracker.recoveriesChargedTo(null))
    }

    @Test
    fun onlyTheMostRecentSignalsDecideWhoIsCharged() {
        // A run outlives a refusal. Its two oldest signals came in on a link that is at its
        // ceiling; once two newer ones have come in on another link, that one is behind most of
        // the last three and the recovery is granted there. Counting every signal ever seen would
        // leave the two links level and the first one, still at its ceiling, would be billed.
        val tracker = SessionFailureTracker(failuresBeforeRecovery = 3, minRecoveryIntervalMs = 100)
        repeat(SessionFailureTracker.MAX_RECOVERIES_PER_INTERVAL_PER_LINK) { peer ->
            repeat(3) { tracker.onDecryptFailed("filler-$peer", it.toLong(), hasSession = true, link = "old") }
        }

        assertNull(tracker.onDecryptFailed(phone, 10, hasSession = true, link = "old"))
        assertNull(tracker.onDecryptFailed(phone, 11, hasSession = true, link = "old"))
        assertNull(tracker.onDecryptFailed(phone, 12, hasSession = true, link = "new"))
        assertEquals(
            SessionFailureTracker.RecoveryReason.UNUSABLE,
            tracker.onDecryptFailed(phone, 13, hasSession = true, link = "new")
        )
        assertEquals(1, tracker.recoveriesChargedTo("new"))
    }
}
