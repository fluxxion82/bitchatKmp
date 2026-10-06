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

    val size: Int
        get() = attempts.size

    /**
     * True when a handshake started at [startedAt] has been in flight past the deadline and can
     * no longer be expected to complete.
     */
    fun isExpired(startedAt: Long, now: Long): Boolean = now - startedAt >= timeoutMs

    /** Record that a handshake message actually went out on the wire. */
    fun recordAttempt(peerID: String, now: Long) {
        val previous = attempts[peerID]
        if (previous == null && attempts.size >= MAX_HANDSHAKE_RECORDS) {
            val oldestPeerID = attempts.minByOrNull { it.value.at }?.key
            if (oldestPeerID != null) attempts.remove(oldestPeerID)
        }
        attempts[peerID] = Attempt(count = (previous?.count ?: 0) + 1, at = now)
    }

    /** Remove spent records unless [keep] says the peer still has an in-flight or owed handshake. */
    fun prune(now: Long, maxAgeMs: Long, keep: (String) -> Boolean): Int {
        val expired = attempts.filter { (peerID, attempt) ->
            now - attempt.at >= maxAgeMs && !keep(peerID)
        }.keys
        expired.forEach(attempts::remove)
        return expired.size
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

        /** How many owed handshakes of each kind are kept; see [OwedHandshakes]. */
        const val MAX_OWED_HANDSHAKES = 256

        /** A spent record is kept this long; after that a new request for the peer starts with a fresh budget. */
        const val HANDSHAKE_RECORD_MAX_AGE_MS = 10 * 60 * 1000L

        /**
         * The record tables have this many entries at most; the oldest goes when a new peer is
         * recorded in a full one. A record is made for every handshake this node starts, and what
         * makes it start one is often not authenticated (a recovery after unreadable packets, a
         * retry of a handshake someone else opened and left), so the age rule alone would not be a
         * limit. What losing a record costs a peer: its history is gone, which means a fresh retry
         * budget the next time something asks for a handshake with it.
         */
        const val MAX_HANDSHAKE_RECORDS = 1024
    }
}
