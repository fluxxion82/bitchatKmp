package com.bitchat.noise

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The sliding window that decides whether a transport nonce has been seen before.
 *
 * This is the only thing standing between the mesh and message replay: a Noise transport message
 * carries its own nonce, packets are relayed with a TTL so the same one arrives more than once, and
 * the outer packet dedup keys on `${peerID}_${timestamp}_${type}`, which two messages in the same
 * millisecond share. Noise's own guarantee (specification section 11.4) is that each authenticated
 * nonce is accepted at most once; this is where that is enforced.
 */
class ReplayProtectionTest {

    private fun window() = ByteArray(NoiseConstants.REPLAY_WINDOW_BYTES)

    /** Accepts [nonce] against the running state, then records it. Returns the new state. */
    private fun accept(nonce: Long, state: Pair<Long, ByteArray>): Pair<Long, ByteArray> {
        assertTrue(
            ReplayProtection.isValidNonce(nonce, state.first, state.second),
            "nonce $nonce should have been accepted"
        )
        return ReplayProtection.markNonceAsSeen(nonce, state.first, state.second)
    }

    private fun rejects(nonce: Long, state: Pair<Long, ByteArray>) {
        assertFalse(
            ReplayProtection.isValidNonce(nonce, state.first, state.second),
            "nonce $nonce should have been rejected as already seen"
        )
    }

    @Test
    fun anAuthenticatedNonceIsNeverAcceptedTwice() {
        var state = 0L to window()
        state = accept(0L, state)
        rejects(0L, state)
    }

    @Test
    fun anOlderNonceIsNotForgottenWhenTheWindowAdvancesByOne() {
        // The case that was broken: 0, then 1, then 0 again. Advancing the window has to carry
        // nonce 0's bit along with it, not shift it out of the window entirely.
        var state = 0L to window()
        state = accept(0L, state)
        state = accept(1L, state)

        rejects(0L, state)
        rejects(1L, state)
    }

    @Test
    fun everyNonceInARunIsRememberedAcrossTheWholeWindow() {
        var state = 0L to window()
        val size = NoiseConstants.REPLAY_WINDOW_SIZE.toLong()

        for (nonce in 0 until size) {
            state = accept(nonce, state)
        }
        // Every one of them must still be refused; none may have been shifted out early.
        for (nonce in 1 until size) {
            rejects(nonce, state)
        }
    }

    @Test
    fun outOfOrderDeliveryIsAcceptedOnceAndThenRefused() {
        // The mesh reorders. A gap must stay open for the packet that fills it, exactly once.
        var state = 0L to window()
        state = accept(0L, state)
        state = accept(5L, state)

        assertTrue(ReplayProtection.isValidNonce(3L, state.first, state.second), "the gap should be open")
        state = accept(3L, state)
        rejects(3L, state)
        rejects(5L, state)
    }

    @Test
    fun aNonceOlderThanTheWindowIsRefused() {
        var state = 0L to window()
        state = accept(0L, state)
        state = accept(NoiseConstants.REPLAY_WINDOW_SIZE.toLong() + 10L, state)

        rejects(0L, state)
        rejects(5L, state)
    }

    @Test
    fun aLargeForwardJumpDoesNotWrapTheOffsetArithmetic() {
        // The offset is computed as a Long difference and then narrowed; a jump larger than Int.MAX
        // must not wrap into a small offset and index the window with it.
        var state = 0L to window()
        state = accept(0L, state)
        state = accept(Int.MAX_VALUE.toLong() + 1_000L, state)

        rejects(0L, state)
    }
}
