package com.bitchat.bluetooth.manager

/**
 * When a Noise session that claims to be established should be torn down and rebuilt.
 *
 * A session can be established here and useless: the peer may have re-keyed after a restart, or an
 * unauthenticated packet may have left our receive nonce set to a counter from a conversation we are
 * not part of, after which every genuine message is refused as going backwards. Nothing noticed. The
 * facade returned null for a failed decrypt, the caller queued the payload, and direct messages from
 * that peer stopped for good while the session still reported itself healthy.
 *
 * So consecutive failures are counted, and a run of them is taken as proof that the session is wrong
 * rather than that the packets are. The count is per peer and resets on the first message that
 * decrypts.
 *
 * A re-handshake is not free and a peer that can forge a packet could otherwise ask for one as often
 * as it liked, so recovery is rate limited: [MIN_RECOVERY_INTERVAL_MS] must pass before the same peer
 * can force another. Pure bookkeeping with no clock -- callers pass `now` in epoch milliseconds --
 * so the rules can be tested on the JVM.
 */
class SessionFailureTracker(
    private val failuresBeforeRecovery: Int = FAILURES_BEFORE_RECOVERY,
    private val minRecoveryIntervalMs: Long = MIN_RECOVERY_INTERVAL_MS
) {

    private val consecutiveFailures = mutableMapOf<String, Int>()
    private val lastRecovery = mutableMapOf<String, Long>()

    /** A message from [peerID] decrypted, so whatever went before was the packets' fault, not ours. */
    fun onDecryptSucceeded(peerID: String) {
        consecutiveFailures.remove(peerID)
    }

    /**
     * A message from [peerID] failed to decrypt at [now]. True when this run of failures is long
     * enough to condemn the session and the peer is not asking too often.
     */
    fun onDecryptFailed(peerID: String, now: Long): Boolean {
        val failures = (consecutiveFailures[peerID] ?: 0) + 1
        consecutiveFailures[peerID] = failures

        if (failures < failuresBeforeRecovery) return false

        val since = lastRecovery[peerID]
        if (since != null && now - since < minRecoveryIntervalMs) return false

        consecutiveFailures.remove(peerID)
        lastRecovery[peerID] = now
        return true
    }

    /** Forget [peerID] entirely, as when its session is replaced or the peer goes away. */
    fun forget(peerID: String) {
        consecutiveFailures.remove(peerID)
        lastRecovery.remove(peerID)
    }

    fun consecutiveFailures(peerID: String): Int = consecutiveFailures[peerID] ?: 0

    companion object {
        /** Matches the upstream Android client, which rebuilds a session after three failures. */
        const val FAILURES_BEFORE_RECOVERY = 3

        /** A peer cannot force handshakes faster than this, however many bad packets it sends. */
        const val MIN_RECOVERY_INTERVAL_MS = 30_000L
    }
}
