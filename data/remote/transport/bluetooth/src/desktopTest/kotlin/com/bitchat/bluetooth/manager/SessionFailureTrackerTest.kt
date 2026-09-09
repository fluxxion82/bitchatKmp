package com.bitchat.bluetooth.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionFailureTrackerTest {

    private val phone = "3b146c2741c9a37e"
    private val other = "269e37bb6be7caf9"

    @Test
    fun aRunOfFailuresCondemnsTheSession() {
        val tracker = SessionFailureTracker()

        assertFalse(tracker.onDecryptFailed(phone, now = 0L))
        assertFalse(tracker.onDecryptFailed(phone, now = 100L))
        assertTrue(tracker.onDecryptFailed(phone, now = 200L))
    }

    @Test
    fun aSingleBadPacketAmongGoodOnesIsNotTheSessionsFault() {
        val tracker = SessionFailureTracker()

        repeat(10) { i ->
            assertFalse(tracker.onDecryptFailed(phone, now = i * 100L))
            tracker.onDecryptSucceeded(phone)
        }
        assertEquals(0, tracker.consecutiveFailures(phone))
    }

    @Test
    fun peersAreCountedSeparately() {
        val tracker = SessionFailureTracker()

        assertFalse(tracker.onDecryptFailed(phone, now = 0L))
        assertFalse(tracker.onDecryptFailed(other, now = 1L))
        assertFalse(tracker.onDecryptFailed(phone, now = 2L))
        assertTrue(tracker.onDecryptFailed(phone, now = 3L))
        assertEquals(1, tracker.consecutiveFailures(other))
    }

    @Test
    fun aPeerCannotForceHandshakesFasterThanTheInterval() {
        val tracker = SessionFailureTracker()

        repeat(3) { assertFalse(tracker.onDecryptFailed(phone, now = 0L) && it < 2) }
        // The third call above returned true and recorded the recovery; a fresh run inside the
        // interval must not produce another, however many bad packets arrive.
        repeat(30) { i ->
            assertFalse(
                tracker.onDecryptFailed(phone, now = 1_000L + i.toLong()),
                "a forged packet should not be able to demand a handshake"
            )
        }
        assertTrue(tracker.onDecryptFailed(phone, now = SessionFailureTracker.MIN_RECOVERY_INTERVAL_MS + 1))
    }

    @Test
    fun forgettingAPeerClearsBothItsCountAndItsRateLimit() {
        val tracker = SessionFailureTracker()

        repeat(3) { tracker.onDecryptFailed(phone, now = 0L) }
        tracker.forget(phone)

        assertEquals(0, tracker.consecutiveFailures(phone))
        assertFalse(tracker.onDecryptFailed(phone, now = 10L))
        assertFalse(tracker.onDecryptFailed(phone, now = 20L))
        assertTrue(tracker.onDecryptFailed(phone, now = 30L))
    }
}
