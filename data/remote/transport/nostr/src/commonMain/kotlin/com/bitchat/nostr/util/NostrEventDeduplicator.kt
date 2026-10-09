package com.bitchat.nostr.util

import com.bitchat.nostr.model.NostrEvent
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.concurrent.Volatile
import kotlin.math.roundToInt

/**
 * The one gate every event a relay sends passes before any subscription handler sees it: it lets
 * an event through to each subscription once, and only if the event is what it says it is.
 *
 * It keeps up to 10,000 event ids. [processEvent] is the only way in, and it records an id only
 * after its event verified, so the ids held are those of genuine events already handed on, and a
 * copy from another relay is recognised without checking a signature again. With each id it keeps
 * how far the event has been handed on: subscriptions are numbered in the order they are made, and
 * one number per event says "every subscription up to here that wants this has had it".
 *
 * - Thread-safe
 * - When full, the id that was verified longest ago goes. Only a verified copy renews an id's
 *   place: a copy that was merely recognised did not prove it is the event, so a relay cannot keep
 *   an id here by repeating it. An id that went costs one more verification, and the subscriptions
 *   that had its event are handed it again
 * - O(1) lookup and insertion
 */
class NostrEventDeduplicator internal constructor(
    private val maxCapacity: Int,
    // Always NostrEvent.isValidSignature in the app. Internal, so that only this module's tests can
    // put something else here: one of them needs to hold several copies inside the check at once.
    private val verifies: (NostrEvent) -> Boolean,
) {
    constructor(maxCapacity: Int = DEFAULT_CAPACITY) : this(maxCapacity, NostrEvent::isValidSignature)

    // Hash map for O(1) lookup - maps event ID to node
    private val nodeMap = ConcurrentMap<String, LRUNode>()

    // Doubly-linked list for LRU ordering
    private val head = LRUNode("HEAD") // Dummy head node
    private val tail = LRUNode("TAIL") // Dummy tail node

    // Lock for thread-safe LRU operations
    private val lruLock = SynchronizedObject()

    // Statistics
    @Volatile
    private var totalChecks = 0L

    @Volatile
    private var duplicateCount = 0L

    @Volatile
    private var evictionCount = 0L

    init {
        // Initialize the doubly-linked list
        head.next = tail
        tail.prev = head

        // Log.d(TAG, "Initialized NostrEventDeduplicator with capacity: $maxCapacity")
    }

    /**
     * Hand [event] to those of the subscriptions [wantedBy] that have not had it, once each, and
     * only if its id and signature verify.
     *
     * A relay is not trusted: the id, key, timestamp, tags and content it sends are claims until
     * the id is recomputed from the fields and the signature checked against the key the event
     * names, which is what [NostrEvent.isValidSignature] does. This holds for every kind. The
     * order is the point:
     *
     * 1. If every subscription in [wantedBy] has had the event with this id, or nobody wants it at
     *    all, the copy is dropped without being verified or recorded. Only verified events are
     *    recorded, and a verified id is the hash of its event, so whatever carries that id now is
     *    the same event again or a forgery of it, and nobody is waiting for either. This is what
     *    keeps the copies the other relays send, and a relay's answer to a subscription asked
     *    again, from costing a signature check.
     * 2. Otherwise the copy is verified, also when the id is one already known to be genuine: a
     *    relay answers a new subscription with what it stores, and what arrives under a genuine
     *    id need not be the genuine event. A copy that fails leaves nothing behind. Were anything
     *    recorded for it, a forged copy that got here first would have the real event dropped as
     *    its duplicate.
     * 3. Only then is it recorded as handed on, and handed to the subscriptions that were owed it.
     *
     * Subscriptions are numbered in the order they are made, and [wantedBy] has to name EVERY
     * subscription there is at this moment that wants the event: what is recorded is the highest
     * number in it, and that stands for all the lower ones.
     *
     * @param processor Called with the event and the subscriptions to hand it to
     * @return what became of the event
     */
    fun processEvent(event: NostrEvent, wantedBy: Set<Long>, processor: (NostrEvent, Set<Long>) -> Unit): EventAdmission {
        totalChecks++
        if (owedTo(event.id, wantedBy, recordThem = false).isEmpty()) return EventAdmission.DUPLICATE
        if (!verifies(event)) return EventAdmission.INVALID
        // Two relays can deliver the same event at once and both get this far; one of them records.
        val owed = owedTo(event.id, wantedBy, recordThem = true)
        if (owed.isEmpty()) return EventAdmission.DUPLICATE
        processor(event, owed)
        return EventAdmission.DELIVERED
    }

    /**
     * Those of [wantedBy] that have not had the event with [eventId]. With [recordThem] they are
     * recorded as having it in the same step, which is done only for a copy that verified; without,
     * nothing changes at all, the id's place in the record included.
     */
    private fun owedTo(eventId: String, wantedBy: Set<Long>, recordThem: Boolean): Set<Long> = synchronized(lruLock) {
        val known = nodeMap[eventId]
        val handedOnUpTo = known?.handedOnUpTo ?: NOBODY
        val owed = wantedBy.filterTo(mutableSetOf()) { it > handedOnUpTo }
        if (owed.isEmpty()) {
            duplicateCount++
        } else if (recordThem) {
            val node = known?.also(::moveToFront) ?: addToFront(eventId)
            node.handedOnUpTo = owed.max()
            if (nodeMap.size > maxCapacity) {
                evictOldest()
            }
        }
        owed
    }

    /**
     * Get current statistics about the deduplicator
     */
    fun getStats(): DeduplicationStats {
        synchronized(lruLock) {
            return DeduplicationStats(
                capacity = maxCapacity,
                currentSize = nodeMap.size,
                totalChecks = totalChecks,
                duplicateCount = duplicateCount,
                evictionCount = evictionCount,
                hitRate = if (totalChecks > 0) (duplicateCount.toDouble() / totalChecks.toDouble()) else 0.0
            )
        }
    }

    /**
     * Clear all cached event IDs (useful for testing or resetting state)
     */
    fun clear() {
        synchronized(lruLock) {
            nodeMap.clear()
            head.next = tail
            tail.prev = head

            // Reset statistics
            totalChecks = 0L
            duplicateCount = 0L
            evictionCount = 0L

            //Log.d(TAG, "Cleared all cached event IDs")
        }
    }

    /**
     * Check if the deduplicator contains a specific event ID
     */
    fun contains(eventId: String): Boolean {
        return nodeMap.containsKey(eventId)
    }

    /**
     * Get the current size of the cache
     */
    fun size(): Int = nodeMap.size

    // MARK: - Private LRU Implementation Methods

    /**
     * Add a new event ID to the front of the LRU list
     */
    private fun addToFront(eventId: String): LRUNode {
        val newNode = LRUNode(eventId)
        nodeMap[eventId] = newNode

        // Insert after head
        newNode.next = head.next
        newNode.prev = head
        head.next?.prev = newNode
        head.next = newNode
        return newNode
    }

    /**
     * Move an existing node to the front (most recently used position)
     */
    private fun moveToFront(node: LRUNode) {
        // Remove from current position
        node.prev?.next = node.next
        node.next?.prev = node.prev

        // Insert at front
        node.next = head.next
        node.prev = head
        head.next?.prev = node
        head.next = node
    }

    /**
     * Remove and return the least recently used node (at the tail)
     */
    private fun removeTail(): LRUNode? {
        val lastNode = tail.prev
        if (lastNode == head) {
            return null // Empty list
        }

        // Remove from linked list
        lastNode?.prev?.next = tail
        tail.prev = lastNode?.prev

        // Remove from hash map
        if (lastNode != null) {
            nodeMap.remove(lastNode.eventId)
        }

        return lastNode
    }

    /**
     * Evict the oldest (least recently used) entries when capacity is exceeded
     */
    private fun evictOldest() {
        while (nodeMap.size > maxCapacity) {
            val evictedNode = removeTail()
            if (evictedNode != null) {
                evictionCount++

                if (evictionCount % 500 == 0L) {
                    // Log.v(TAG, "Evicted event ID: ${evictedNode.eventId} (${evictionCount} total evictions)")
                }
            } else {
                break // Should not happen, but safety check
            }
        }
    }

    companion object {
        private const val TAG = "NostrDeduplicator"
        private const val DEFAULT_CAPACITY = 10_000

        /** Lower than any subscription's number: the event has been handed to nobody. */
        private const val NOBODY = Long.MIN_VALUE
    }

    /**
     * Node for the doubly-linked list used in LRU implementation. [handedOnUpTo] is the highest
     * numbered subscription the event was handed to: every lower one that wants it has had it.
     */
    private class LRUNode(
        val eventId: String,
        var prev: LRUNode? = null,
        var next: LRUNode? = null,
        var handedOnUpTo: Long = NOBODY,
    )
}

/** What [NostrEventDeduplicator.processEvent] did with an event. */
enum class EventAdmission {
    /** Its id and signature verified and a subscription that wants it had not had it: the processor ran. */
    DELIVERED,

    /** An event with this id verified earlier and every subscription that wants it has had it; this copy was not looked at. */
    DUPLICATE,

    /** Its id is not the hash of its fields, or its signature is missing or wrong. Nothing was recorded. */
    INVALID,
}

/**
 * Statistics about the deduplication system
 */
data class DeduplicationStats(
    val capacity: Int,
    val currentSize: Int,
    val totalChecks: Long,
    val duplicateCount: Long,
    val evictionCount: Long,
    val hitRate: Double
) {
    override fun toString(): String {
        return "DeduplicationStats(capacity=$capacity, size=$currentSize, " +
                "checks=$totalChecks, duplicates=$duplicateCount, evictions=$evictionCount, " +
                "hitRate=${hitRate.toPercentageString()}%)"
        // "hitRate=${"%.2f".format(hitRate * 100)}%)"
    }

    fun Double.toPercentageString(): String {
        val percentage = this * 100
        val rounded = (percentage * 100).roundToInt() / 100.0
        return rounded.toString()
    }

}
