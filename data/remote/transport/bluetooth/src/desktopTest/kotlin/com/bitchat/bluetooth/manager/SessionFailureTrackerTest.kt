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
    fun recoveryCeilingsPruneNoSessionEntries() {
        val tracker = SessionFailureTracker(failuresBeforeRecovery = 99, minRecoveryIntervalMs = 100)
        repeat(SessionFailureTracker.MAX_RECOVERIES_PER_INTERVAL_WITHOUT_SESSION) {
            assertEquals(SessionFailureTracker.RecoveryReason.UNUSABLE, tracker.onDecryptFailed("no-session-$it", 0, hasSession = false))
        }
        assertNull(tracker.onDecryptFailed("waiting", 0, hasSession = false))
        assertEquals(SessionFailureTracker.RecoveryReason.UNUSABLE, tracker.onDecryptFailed("waiting", 100, hasSession = false))
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
    fun seventeenthSessionBackedRecoveryWaitsForTheInterval() {
        val tracker = SessionFailureTracker(failuresBeforeRecovery = 1, minRecoveryIntervalMs = 100)
        repeat(SessionFailureTracker.MAX_RECOVERIES_PER_INTERVAL_WITH_SESSION) {
            assertEquals(SessionFailureTracker.RecoveryReason.UNUSABLE, tracker.onDecryptFailed("session-$it", 0, hasSession = true))
        }
        assertNull(tracker.onDecryptFailed("held", 0, hasSession = true))
        assertEquals(1, tracker.consecutiveFailures("held"))
        assertEquals(SessionFailureTracker.RecoveryReason.UNUSABLE, tracker.onDecryptFailed("held", 100, hasSession = true))
        assertEquals(1, tracker.recoveryCount)
    }
}
