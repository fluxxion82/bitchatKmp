package com.bitchat.repo.utils

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * A thread-safe insertion-ordered set with a fixed size. Forgetting an id can apply a repeated
 * receipt or acknowledgement again, which is harmless; refusing a new id would let old traffic
 * block a newly received receipt or acknowledgement.
 */
class BoundedIdSet(private val capacity: Int) {
    init {
        require(capacity > 0) { "capacity must be positive, was $capacity" }
    }

    private val ids = LinkedHashSet<String>()
    private val lock = SynchronizedObject()

    /** True when [id] was newly recorded. The id added longest ago is forgotten when full. */
    fun add(id: String): Boolean = synchronized(lock) {
        if (!ids.add(id)) return@synchronized false
        if (ids.size > capacity) ids.iterator().run { next(); remove() }
        true
    }

    fun clear() = synchronized(lock) { ids.clear() }

    val size: Int get() = synchronized(lock) { ids.size }
}
