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
        // The third call above returned true and recorded the recovery; a fresh run inside the
        // interval must not produce another, however many bad packets arrive.
        repeat(30) { i ->
            assertNull(
                tracker.onDecryptFailed(phone, now = 1_000L + i.toLong()),
                "a forged packet should not be able to demand a handshake"
            )
        }
        assertEquals(SessionFailureTracker.RecoveryReason.UNUSABLE, tracker.onDecryptFailed(phone, now = SessionFailureTracker.MIN_RECOVERY_INTERVAL_MS + 1))
    }

    @Test
    fun aRunThatIncludesAFallbackOnlyDecryptSaysTheSessionIsNotShared() {
        val tracker = SessionFailureTracker()
        val fallback = SessionFailureTracker.FailureEvidence.FALLBACK_SUCCESS

        // Two undecryptable payloads and one the peer authenticated with the previous session:
        // that one is proof of which session the peer uses, so it decides what the run means.
        assertNull(tracker.onDecryptFailed(phone, now = 0L))
        assertNull(tracker.onDecryptFailed(phone, now = 1L, evidence = fallback))
        assertEquals(SessionFailureTracker.RecoveryReason.NOT_SHARED, tracker.onDecryptFailed(phone, now = 2L))
    }

    @Test
    fun aDecryptViaTheEstablishedSessionEndsARunOfEitherKind() {
        val tracker = SessionFailureTracker()
        val fallback = SessionFailureTracker.FailureEvidence.FALLBACK_SUCCESS

        tracker.onDecryptFailed(phone, now = 0L, evidence = fallback)
        tracker.onDecryptFailed(phone, now = 1L)
        tracker.onDecryptSucceeded(phone)

        assertEquals(0, tracker.consecutiveFailures(phone))
        // The earlier fallback evidence went with the run it belonged to.
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
}
