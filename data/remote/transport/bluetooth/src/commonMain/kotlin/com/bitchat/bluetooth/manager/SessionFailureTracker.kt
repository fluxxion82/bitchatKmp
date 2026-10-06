package com.bitchat.bluetooth.manager

/**
 * When a Noise session that claims to be established should be demoted and replaced.
 *
 * A session can be established here and useless: the peer may have re-keyed after a restart, or an
 * unauthenticated packet may have left our receive nonce set to a counter from a conversation we are
 * not part of, after which every genuine message is refused as going backwards. Nothing noticed. The
 * facade returned null for a failed decrypt, the caller queued the payload, and direct messages from
 * that peer stopped for good while the session still reported itself healthy.
 *
 * So consecutive undecryptable payloads and fallback-only decrypts beside a newer established
 * session are counted. The latter proves the peer still uses the predecessor, so it must not
 * confirm that the newer session is shared. The fallback never sends; outgoing traffic waits for
 * the new established session. The count is per peer and resets only on a decrypt via the
 * established session.
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

    enum class FailureEvidence {
        UNDECRYPTABLE,
        FALLBACK_SUCCESS
    }

    enum class RecoveryReason {
        UNUSABLE,
        NOT_SHARED
    }

    private data class FailureRun(
        val count: Int,
        val includesFallbackSuccess: Boolean
    )

    private val consecutiveFailures = mutableMapOf<String, FailureRun>()
    private val lastRecovery = mutableMapOf<String, Long>()

    /** A message from [peerID] decrypted via the established session, so the run is over. */
    fun onDecryptSucceeded(peerID: String) {
        consecutiveFailures.remove(peerID)
    }

    /**
     * Records evidence at [now]. A qualifying run containing a fallback decrypt reports
     * [RecoveryReason.NOT_SHARED]; otherwise it reports [RecoveryReason.UNUSABLE].
     */
    fun onDecryptFailed(
        peerID: String,
        now: Long,
        evidence: FailureEvidence = FailureEvidence.UNDECRYPTABLE
    ): RecoveryReason? {
        val previous = consecutiveFailures[peerID]
        val failures = (previous?.count ?: 0) + 1
        val includesFallbackSuccess = previous?.includesFallbackSuccess == true || evidence == FailureEvidence.FALLBACK_SUCCESS
        consecutiveFailures[peerID] = FailureRun(failures, includesFallbackSuccess)

        if (failures < failuresBeforeRecovery) return null

        val since = lastRecovery[peerID]
        if (since != null && now - since < minRecoveryIntervalMs) return null

        consecutiveFailures.remove(peerID)
        lastRecovery[peerID] = now
        return if (includesFallbackSuccess) RecoveryReason.NOT_SHARED else RecoveryReason.UNUSABLE
    }

    /** Forget [peerID] entirely during explicit teardown, such as when the peer goes away. */
    fun forget(peerID: String) {
        consecutiveFailures.remove(peerID)
        lastRecovery.remove(peerID)
    }

    fun consecutiveFailures(peerID: String): Int = consecutiveFailures[peerID]?.count ?: 0

    companion object {
        /** Matches the upstream Android client, which rebuilds a session after three failures. */
        const val FAILURES_BEFORE_RECOVERY = 3

        /** A peer cannot force handshakes faster than this, however many bad packets it sends. */
        const val MIN_RECOVERY_INTERVAL_MS = 30_000L
    }
}
