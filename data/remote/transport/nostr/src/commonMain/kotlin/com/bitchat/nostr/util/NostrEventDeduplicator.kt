package com.bitchat.nostr.util

import com.bitchat.nostr.model.NostrEvent
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.concurrent.Volatile
import kotlin.math.roundToInt

/**
 * The one gate every event a relay sends passes before any subscription handler sees it: it lets
 * an event through once, and only if the event is what it says it is.
 *
 * It keeps an LRU of up to 10,000 event ids. [processEvent] is the only way in, and it records an
 * id only after its event verified, so the ids held are those of genuine events already handed on,
 * and a copy from another relay is recognised without checking a signature again.
 *
 * - Thread-safe
 * - LRU eviction when capacity is exceeded; an evicted id only costs one more verification
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
     * Hand [event] to [processor] once, and only if its id and signature verify.
     *
     * A relay is not trusted: the id, key, timestamp, tags and content it sends are claims until
     * the id is recomputed from the fields and the signature checked against the key the event
     * names, which is what [NostrEvent.isValidSignature] does. This holds for every kind. The
     * order is the point:
     *
     * 1. An id already recorded is dropped without being verified. Only verified events are
     *    recorded, and a verified id is the hash of its event, so whatever carries that id now is
     *    the same event again or a forgery of it, and neither is wanted. This is what keeps the
     *    copies the other relays send from costing a signature check each.
     * 2. Anything else is verified, and an event that fails leaves nothing behind. Were its id
     *    recorded, a forged copy that got here first would have the real event dropped as its
     *    duplicate.
     * 3. Only then is the id recorded and the event handed on.
     *
     * @param event The Nostr event to process
     * @param processor Called if the event verified and was not delivered before
     * @return what became of the event
     */
    fun processEvent(event: NostrEvent, processor: (NostrEvent) -> Unit): EventAdmission {
        totalChecks++
        if (isRecorded(event.id)) return EventAdmission.DUPLICATE
        if (!verifies(event)) return EventAdmission.INVALID
        // Two relays can deliver the same event at once and both get this far; one of them records.
        if (!recordIfNew(event.id)) return EventAdmission.DUPLICATE
        processor(event)
        return EventAdmission.DELIVERED
    }

    /** Whether [eventId] is recorded; a hit counts as a use, a miss records nothing. */
    private fun isRecorded(eventId: String): Boolean = synchronized(lruLock) {
        val existingNode = nodeMap[eventId] ?: return@synchronized false
        moveToFront(existingNode)
        duplicateCount++
        true
    }

    /** Records [eventId] unless it is recorded already, in one step. Returns whether it was new. */
    private fun recordIfNew(eventId: String): Boolean = synchronized(lruLock) {
        val existingNode = nodeMap[eventId]
        if (existingNode != null) {
            moveToFront(existingNode)
            duplicateCount++
            return@synchronized false
        }
        addToFront(eventId)
        if (nodeMap.size > maxCapacity) {
            evictOldest()
        }
        true
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
    private fun addToFront(eventId: String) {
        val newNode = LRUNode(eventId)
        nodeMap[eventId] = newNode

        // Insert after head
        newNode.next = head.next
        newNode.prev = head
        head.next?.prev = newNode
        head.next = newNode
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
    }

    /**
     * Node for the doubly-linked list used in LRU implementation
     */
    private data class LRUNode(
        val eventId: String,
        var prev: LRUNode? = null,
        var next: LRUNode? = null
    )
}

/** What [NostrEventDeduplicator.processEvent] did with an event. */
enum class EventAdmission {
    /** Its id and signature verified and it had not been delivered before: the processor ran. */
    DELIVERED,

    /** An event with this id verified and was delivered earlier; this copy was not looked at. */
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
