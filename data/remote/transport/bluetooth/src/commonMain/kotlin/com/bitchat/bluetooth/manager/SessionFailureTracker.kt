package com.bitchat.bluetooth.manager

import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock

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
 * A peer without a validated session has no run worth protecting from a stray unreadable packet.
 * Its first unreadable packet asks for recovery; the recovery ceiling limits invented sender ids.
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

    private data class Recovery(val at: Long, val hasSession: Boolean)

    private val lock = ReentrantLock()
    private val failureRuns = LinkedHashMap<String, FailureRun>()
    private val lastRecovery = mutableMapOf<String, Recovery>()

    internal val failureRunCount: Int get() = lock.withLock { failureRuns.size }
    internal val recoveryCount: Int get() = lock.withLock { lastRecovery.size }

    /** A message from [peerID] decrypted via the established session, so the run is over. */
    fun onDecryptSucceeded(peerID: String) {
        lock.withLock {
            failureRuns.remove(peerID)
        }
    }

    /**
     * Records evidence at [now]. A qualifying run containing a fallback decrypt reports
     * [RecoveryReason.NOT_SHARED]; otherwise it reports [RecoveryReason.UNUSABLE].
     */
    fun onDecryptFailed(
        peerID: String,
        now: Long,
        evidence: FailureEvidence = FailureEvidence.UNDECRYPTABLE,
        hasSession: Boolean = true
    ): RecoveryReason? = lock.withLock {
        val includesFallbackSuccess = if (hasSession) {
            val previous = failureRuns.remove(peerID)
            val failures = (previous?.count ?: 0) + 1
            val includesFallback = previous?.includesFallbackSuccess == true || evidence == FailureEvidence.FALLBACK_SUCCESS
            if (failureRuns.size >= MAX_FAILURE_RUNS) failureRuns.entries.iterator().run { next(); remove() }
            failureRuns[peerID] = FailureRun(failures, includesFallback)
            if (failures < failuresBeforeRecovery) return@withLock null
            includesFallback
        } else {
            // A lost session must not leave a stale run that a forged packet could carry forward.
            failureRuns.remove(peerID)
            false
        }

        val since = lastRecovery[peerID]
        val peerIsRateLimited = since != null && now - since.at < minRecoveryIntervalMs
        lastRecovery.entries.removeAll { now - it.value.at >= minRecoveryIntervalMs }
        if (peerIsRateLimited) return@withLock null
        val ceiling = if (hasSession) MAX_RECOVERIES_PER_INTERVAL_WITH_SESSION else MAX_RECOVERIES_PER_INTERVAL_WITHOUT_SESSION
        val recoveryClassCount = lastRecovery.values.count { it.hasSession == hasSession }
        if (recoveryClassCount >= ceiling) return@withLock null

        failureRuns.remove(peerID)
        lastRecovery[peerID] = Recovery(now, hasSession)
        return if (includesFallbackSuccess) RecoveryReason.NOT_SHARED else RecoveryReason.UNUSABLE
    }

    /** Forget [peerID] entirely during explicit teardown, such as when the peer goes away. */
    fun forget(peerID: String) {
        lock.withLock {
            failureRuns.remove(peerID)
            lastRecovery.remove(peerID)
        }
    }

    fun consecutiveFailures(peerID: String): Int = lock.withLock {
        failureRuns[peerID]?.count ?: 0
    }

    companion object {
        /** Matches the upstream Android client, which rebuilds a session after three failures. */
        const val FAILURES_BEFORE_RECOVERY = 3

        /** A peer cannot force handshakes faster than this, however many bad packets it sends. */
        const val MIN_RECOVERY_INTERVAL_MS = 30_000L

        /**
         * Each run is a few words. This limit exists so the table is bounded at all; it is above
         * any number of sessions this app can sensibly hold, and when full its least recently
         * updated run goes.
         */
        const val MAX_FAILURE_RUNS = 1024

        /**
         * How many recoveries may be granted inside one interval, counted separately for peers a
         * validated session is on file for and for peers with none. Every recovery makes this node
         * start a handshake, and the packets that ask for one are not authenticated, so without a
         * ceiling invented sender ids could make it transmit as often as they liked. For a peer
         * without a session one forged packet is enough to ask, so the ceiling is the only thing
         * between invented sender ids and this node starting handshakes. The trade is unchanged:
         * a genuine recovery waits while its class is at the ceiling; a user sending a message
         * still starts a handshake, and that path does not go through here.
         */
        const val MAX_RECOVERIES_PER_INTERVAL_WITH_SESSION = 16
        const val MAX_RECOVERIES_PER_INTERVAL_WITHOUT_SESSION = 16
    }
}
