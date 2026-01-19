package com.bitchat.cache.impl

import com.bitchat.cache.Cache
import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock

class ThreadSafeCache<in Key : Any, T>(
    private val cache: Cache<Key, T>,
) : Cache<Key, T> by cache {
    private val lock = ReentrantLock()

    override operator fun get(key: Key): T? =
        lock.withLock { cache[key] }

    override operator fun set(key: Key, value: T) {
        lock.withLock { cache[key] = value }
    }

    override fun getAllValues(): List<T> =
        lock.withLock { cache.getAllValues().toList() }

    override fun getAll(): Map<in Key, T> =
        lock.withLock { cache.getAll() }

    override fun removeAll() {
        lock.withLock { cache.removeAll() }
    }

    override fun remove(key: Key) {
        lock.withLock { cache.remove(key) }
    }
}
