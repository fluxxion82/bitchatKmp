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
        val includesFallbackSuccess: Boolean,
        /** The links the most recent signals arrived on, oldest first; as many as the threshold. */
        val links: List<String>
    )

    /** [link] is null when no single link was behind most of the signals (see [linkToCharge]). */
    private data class Recovery(val at: Long, val hasSession: Boolean, val link: String?)

    private val lock = ReentrantLock()
    private val failureRuns = LinkedHashMap<String, FailureRun>()
    private val lastRecovery = mutableMapOf<String, Recovery>()

    internal val failureRunCount: Int get() = lock.withLock { failureRuns.size }
    internal val recoveryCount: Int get() = lock.withLock { lastRecovery.size }

    /** Recoveries charged to [link] (null: to no single link), whatever their age; for tests. */
    internal fun recoveriesChargedTo(link: String?): Int = lock.withLock { lastRecovery.values.count { it.link == link } }

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
        hasSession: Boolean = true,
        link: String = ""
    ): RecoveryReason? = lock.withLock {
        val includesFallbackSuccess: Boolean
        // The link a recovery is charged to. The signals behind one recovery may have arrived on
        // different links, and the link of the last one is the wrong answer: two unreadable packets
        // on one link followed by one on another would bill the second for what the first did.
        val chargedLink: String?
        if (hasSession) {
            val previous = failureRuns.remove(peerID)
            val failures = (previous?.count ?: 0) + 1
            includesFallbackSuccess = previous?.includesFallbackSuccess == true || evidence == FailureEvidence.FALLBACK_SUCCESS
            val links = ((previous?.links ?: emptyList()) + link).takeLast(failuresBeforeRecovery)
            if (failureRuns.size >= MAX_FAILURE_RUNS) failureRuns.entries.iterator().run { next(); remove() }
            failureRuns[peerID] = FailureRun(failures, includesFallbackSuccess, links)
            if (failures < failuresBeforeRecovery) return@withLock null
            chargedLink = linkToCharge(links)
        } else {
            // A lost session must not leave a stale run that a forged packet could carry forward.
            failureRuns.remove(peerID)
            includesFallbackSuccess = false
            chargedLink = link
        }

        val since = lastRecovery[peerID]
        lastRecovery.entries.removeAll { now - it.value.at >= minRecoveryIntervalMs }
        if (since != null && now - since.at < minRecoveryIntervalMs) return@withLock null
        val linkRecoveryCount = lastRecovery.values.count { it.hasSession == hasSession && it.link == chargedLink }
        if (linkRecoveryCount >= MAX_RECOVERIES_PER_INTERVAL_PER_LINK) return@withLock null
        val ceiling = if (hasSession) MAX_RECOVERIES_PER_INTERVAL_WITH_SESSION else MAX_RECOVERIES_PER_INTERVAL_WITHOUT_SESSION
        val recoveryClassCount = lastRecovery.values.count { it.hasSession == hasSession }
        if (recoveryClassCount >= ceiling) return@withLock null

        failureRuns.remove(peerID)
        lastRecovery[peerID] = Recovery(now, hasSession, chargedLink)
        return if (includesFallbackSuccess) RecoveryReason.NOT_SHARED else RecoveryReason.UNUSABLE
    }

    /**
     * The link behind MORE THAN HALF of [links], or null when there is none. With no such link the
     * recovery is charged to a separate allowance shared by all such mixed runs, never to one of
     * the links: with signals from three links, billing any one of them (the first, the last) would
     * let two packets on two other links spend the allowance of a link that sent one.
     */
    private fun linkToCharge(links: List<String>): String? {
        val lead = links.distinct().maxByOrNull { candidate -> links.count { it == candidate } } ?: return null
        return lead.takeIf { links.count { it == lead } * 2 > links.size }
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
         * Every recovery makes this node start a handshake, and the packet that asks for one is
         * not authenticated. So recoveries are limited, and the limit is kept per LINK (the address
         * of the connection the signals arrived on), because a sender can invent ids and cannot
         * invent links: unreadable packets arriving on one link cannot use up another link's
         * recoveries.
         *
         * A link is charged for a recovery only when it is behind more than half of the signals
         * that led to it. A run that no single link is behind is charged to one further allowance
         * shared by all such runs. What this costs, stated plainly:
         *  - a link behind the recoveries of more than eight peers inside one interval (a relay
         *    with many peers behind it after this node restarted, or a link being flooded) has the
         *    rest wait for a later signal after a slot frees;
         *  - one forged packet can complete a run that already holds two genuine signals. If those
         *    two came over one link, that link is charged, for a recovery its own traffic was two
         *    thirds of. If they came over two links, the shared allowance is charged, and eight
         *    such runs inside an interval make other runs spread over several links wait. A peer
         *    whose signals all arrive over one link, which is the ordinary case, never needs the
         *    shared allowance: it waits only when its own link's allowance is used up (which the
         *    previous point lets a forger hasten, one packet per run that was already two thirds
         *    genuine) or when the total is reached;
         *  - the totals are 64 recoveries per class, which no single allowance can supply: at
         *    least eight of them have to be at their ceilings.
         * A user sending a message still starts a handshake; that path does not come through here.
         */
        const val MAX_RECOVERIES_PER_INTERVAL_PER_LINK = 8
        const val MAX_RECOVERIES_PER_INTERVAL_WITH_SESSION = 64
        const val MAX_RECOVERIES_PER_INTERVAL_WITHOUT_SESSION = 64
    }
}
