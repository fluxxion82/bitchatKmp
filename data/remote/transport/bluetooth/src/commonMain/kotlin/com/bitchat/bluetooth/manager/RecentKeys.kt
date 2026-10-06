package com.bitchat.bluetooth.manager

import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock

/**
 * Which keys were seen recently: at most [capacity] of them, each for [timeToLiveMs].
 *
 * The records are kept in the order they were made, oldest first, and that order is the only thing
 * that decides which one goes when the table is full: always the oldest. A key seen again after it
 * expired is a new record and moves to the newest end. A forgotten key gives its place back, so
 * recording and forgetting one key over and over reuses that one place: at most the first time, in
 * a full table, does it cost another key its record.
 *
 * The order is a linked list threaded through arrays, so recording and forgetting never walk the
 * table, on any platform. (An insertion-ordered map would be shorter, but on Kotlin/Native removing
 * its first entry leaves a gap that the next lookup of "the first entry" has to walk past.)
 * [capacity] must be positive.
 */
class RecentKeys(private val capacity: Int, private val timeToLiveMs: Long) {
    private val lock = ReentrantLock()

    private val slotOf = HashMap<String, Int>()
    private val keys = arrayOfNulls<String>(capacity)
    private val seenAt = LongArray(capacity)

    // The list: for each slot in use, its neighbours towards the oldest and the newest record.
    private val older = IntArray(capacity)
    private val newer = IntArray(capacity)
    private var oldest = NONE
    private var newest = NONE

    // Slots never handed out yet are [neverUsed, capacity); slots given back wait in freed.
    private var neverUsed = 0
    private val freed = IntArray(capacity)
    private var freedCount = 0

    val size: Int get() = lock.withLock { slotOf.size }

    fun firstSighting(key: String, now: Long): Boolean = lock.withLock {
        val known = slotOf[key]
        if (known != null) {
            if (now - seenAt[known] < timeToLiveMs) return@withLock false
            // Expired, so this is a new record: the newest one, wherever its slot happens to be.
            unlink(known)
            seenAt[known] = now
            linkAsNewest(known)
            return@withLock true
        }

        val slot = when {
            freedCount > 0 -> freed[--freedCount]
            neverUsed < capacity -> neverUsed++
            else -> oldest.also { full ->
                slotOf.remove(keys[full])
                unlink(full)
            }
        }
        keys[slot] = key
        seenAt[slot] = now
        slotOf[key] = slot
        linkAsNewest(slot)
        true
    }

    fun forget(key: String) {
        lock.withLock {
            val slot = slotOf.remove(key) ?: return@withLock
            unlink(slot)
            keys[slot] = null
            freed[freedCount++] = slot
        }
    }

    fun clear() = lock.withLock {
        slotOf.clear()
        keys.fill(null)
        oldest = NONE
        newest = NONE
        neverUsed = 0
        freedCount = 0
    }

    private fun unlink(slot: Int) {
        val towardsOldest = older[slot]
        val towardsNewest = newer[slot]
        if (towardsOldest == NONE) oldest = towardsNewest else newer[towardsOldest] = towardsNewest
        if (towardsNewest == NONE) newest = towardsOldest else older[towardsNewest] = towardsOldest
    }

    private fun linkAsNewest(slot: Int) {
        older[slot] = newest
        newer[slot] = NONE
        if (newest == NONE) oldest = slot else newer[newest] = slot
        newest = slot
    }

    private companion object {
        const val NONE = -1
    }
}
