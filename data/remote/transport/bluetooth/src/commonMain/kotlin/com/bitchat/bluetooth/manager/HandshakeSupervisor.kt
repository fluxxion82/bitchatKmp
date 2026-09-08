package com.bitchat.bluetooth.manager

/**
 * Deadline and retry budget for Noise handshakes that are in flight.
 *
 * A Noise XX handshake has no acknowledgement of its own: the initiator sends message 1 and
 * either message 2 comes back or the session sits in `Handshaking` forever. On a BLE mesh a
 * single lost frame is routine — a link drops, a central rotates its address — so the state
 * machine needs an exit that does not depend on the peer answering.
 *
 * This class holds only the bookkeeping, so the policy can be tested without a radio: it has no
 * clock and no coroutines. Callers pass `now` in epoch milliseconds, the same base as
 * [com.bitchat.noise.NoiseSession.getCreationTime].
 *
 * The budget is what keeps a retry from becoming a storm against a peer that is genuinely gone:
 * an attempt is only allowed once the backoff for the attempts already made has elapsed, and only
 * while attempts remain. The backoff doubles from [timeoutMs] up to [maxBackoffMs], so with the
 * defaults a peer that never answers costs five packets spread over roughly two and a half
 * minutes and then nothing at all until something resets it.
 */
class HandshakeSupervisor(
    private val timeoutMs: Long = HANDSHAKE_TIMEOUT_MS,
    private val maxAttempts: Int = MAX_ATTEMPTS,
    private val maxBackoffMs: Long = MAX_BACKOFF_MS
) {

    private data class Attempt(val count: Int, val at: Long)

    private val attempts = mutableMapOf<String, Attempt>()

    /**
     * True when a handshake started at [startedAt] has been in flight past the deadline and can
     * no longer be expected to complete.
     */
    fun isExpired(startedAt: Long, now: Long): Boolean = now - startedAt >= timeoutMs

    /** Record that a handshake message actually went out on the wire. */
    fun recordAttempt(peerID: String, now: Long) {
        val previous = attempts[peerID]
        attempts[peerID] = Attempt(count = (previous?.count ?: 0) + 1, at = now)
    }

    /**
     * True when an automatic retry for [peerID] is within budget: attempts remain and the backoff
     * for the attempts already made has elapsed. A peer we have never tried is always allowed.
     */
    fun mayAttempt(peerID: String, now: Long): Boolean {
        val previous = attempts[peerID] ?: return true
        if (previous.count >= maxAttempts) return false
        return now - previous.at >= backoffFor(previous.count)
    }

    /** True when the retry budget for [peerID] is spent and only an external event can revive it. */
    fun isExhausted(peerID: String): Boolean = attemptsFor(peerID) >= maxAttempts

    fun attemptsFor(peerID: String): Int = attempts[peerID]?.count ?: 0

    /**
     * Forget the attempts made against [peerID]. Called when a session is established, when the
     * peer reappears on a fresh link, or when it leaves: in each case the history says nothing
     * about whether the next attempt will land.
     */
    fun reset(peerID: String) {
        attempts.remove(peerID)
    }

    fun clear() {
        attempts.clear()
    }

    /** Delay required after [attemptCount] attempts: [timeoutMs] doubling up to [maxBackoffMs]. */
    internal fun backoffFor(attemptCount: Int): Long {
        if (attemptCount <= 0) return 0L
        var backoff = timeoutMs
        repeat(attemptCount - 1) {
            if (backoff >= maxBackoffMs) return maxBackoffMs
            backoff *= 2
        }
        return if (backoff > maxBackoffMs) maxBackoffMs else backoff
    }

    companion object {
        /**
         * How long a handshake may sit in flight before it is abandoned.
         *
         * A healthy exchange on this mesh completes inside a second — the device journal shows
         * 32 -> 96 -> 64 bytes and an established session within the same second — so ten seconds
         * is an order of magnitude of headroom over a slow round trip, including the relay hops a
         * TTL-3 packet may take and the serialisation of the per-peer packet actor. It is also
         * short enough that a person who sends a direct message into a link that has just died
         * waits seconds for the retry rather than the rest of the process's life.
         */
        const val HANDSHAKE_TIMEOUT_MS = 10_000L

        /** How often the deadline is checked. Detection latency is at most one interval. */
        const val SWEEP_INTERVAL_MS = 5_000L

        /** Automatic attempts against one peer before giving up until something resets it. */
        const val MAX_ATTEMPTS = 5

        /** Ceiling on the doubling backoff. */
        const val MAX_BACKOFF_MS = 120_000L
    }
}
