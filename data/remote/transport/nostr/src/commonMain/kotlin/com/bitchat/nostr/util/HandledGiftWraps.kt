package com.bitchat.nostr.util

import com.bitchat.nostr.NostrClient
import com.bitchat.nostr.model.NostrEvent
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.time.Clock

/**
 * The gift wraps (NIP-59, kind 1059) already accepted, so that a DM which arrives from several
 * relays, or is replayed each time a relay restores the subscription, is processed once.
 *
 * "Handled" means authenticated and accepted, not merely seen: an id is recorded only after the
 * authenticate step passed to [acceptOnce] (NostrClient.decryptPrivateMessage) returned a result
 * for that envelope. Anyone can put a real message's id on a garbage or forged envelope; if seeing
 * that copy first were enough, the real message would then be dropped as its duplicate. A rejected
 * envelope leaves nothing behind, so it cannot suppress a later valid one. An accepted one is bound
 * to its id: the id is the hash of the signed fields and the signature verified, so any later
 * envelope with that id that also authenticates is the same message, and dropping it is right.
 *
 * Retention: an id is kept until its wrap is older than [retentionSeconds], by default the oldest
 * wrap decryptPrivateMessage accepts (48h15m). Past that age a wrap is refused here before
 * authentication, as it is in NostrClient, so dropping an expired id never lets its message through
 * again. A five-minute window would not do for DMs: NIP-59 backdates created_at by up to two days,
 * and the DM subscription has no `since`, so a relay replays its latest 100 wraps on every resubscribe.
 *
 * Bound: at most [capacity] ids, so a flood of envelopes, accepted or not, cannot grow this past
 * about 1 MB (a 64-character id, its map entry and expiry: roughly 250 bytes). Room is made from
 * expired ids first, and only then by dropping the least recently seen one. Dropping a fresh id
 * costs replay suppression for that one message - at worst it is shown twice - while refusing the
 * new wrap instead would cost delivery: anyone can seal a wrap this client accepts, so [capacity]
 * of them would otherwise black out every real DM for as long as the retention window.
 *
 * Thread-safe: relay handlers run concurrently. The lock is not held while authenticating.
 */
class HandledGiftWraps(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val retentionSeconds: Long = NostrClient.GIFT_WRAP_MAX_AGE_SECONDS.toLong(),
    private val nowEpochSeconds: () -> Long = { Clock.System.now().epochSeconds },
) {
    init {
        require(capacity > 0) { "capacity must be positive, was $capacity" }
    }

    /** Id to the epoch second after which its wrap is too old to accept; least recently seen first. */
    private val expiries = LinkedHashMap<String, Long>()
    private val lock = SynchronizedObject()

    /**
     * What [authenticate] returned for [giftWrap], if this call is the one that should process it.
     * Null if the wrap is too old, [authenticate] rejected it, or a wrap with its id was already
     * accepted, including by a concurrent call that finished authenticating first.
     */
    fun <T : Any> acceptOnce(giftWrap: NostrEvent, authenticate: (NostrEvent) -> T?): T? {
        val expiresAt = giftWrap.createdAt.toLong() + retentionSeconds
        if (nowEpochSeconds() > expiresAt) return null
        if (synchronized(lock) { touch(giftWrap.id) }) return null

        val accepted = authenticate(giftWrap) ?: return null

        return if (synchronized(lock) { record(giftWrap.id, expiresAt) }) accepted else null
    }

    fun size(): Int = synchronized(lock) { expiries.size }

    fun clear() = synchronized(lock) { expiries.clear() }

    /** Marks [id] as just seen. False if it is not recorded. */
    private fun touch(id: String): Boolean {
        val expiresAt = expiries.remove(id) ?: return false
        expiries[id] = expiresAt
        return true
    }

    /** Records [id] unless a concurrent call already did. False if it was already recorded. */
    private fun record(id: String, expiresAt: Long): Boolean {
        if (touch(id)) return false
        if (expiries.size >= capacity) {
            val now = nowEpochSeconds()
            expiries.values.removeAll { it < now }
        }
        // Still full, so every retained id is fresh: give up the least recently seen one rather
        // than this message. See the class comment on which of the two costs is acceptable.
        while (expiries.size >= capacity) {
            val oldest = expiries.keys.iterator()
            oldest.next()
            oldest.remove()
        }
        expiries[id] = expiresAt
        return true
    }

    companion object {
        /**
         * Sized for what has to stay recorded while a wrap is acceptable. Replays: a relay re-sends
         * up to 100 wraps per DM identity (the account's, and one per joined geohash) when it
         * restores the subscription, and 4096 holds that for about 40 relay and identity pairs.
         * Live traffic: senders backdate created_at by up to two days (uniformly, in this client),
         * so an id stays here about a day on average, and 4096 covers about 4000 wraps a day: over
         * 1300 DMs a day even if each one also brought a delivery ack and a read receipt.
         */
        const val DEFAULT_CAPACITY = 4096
    }
}
