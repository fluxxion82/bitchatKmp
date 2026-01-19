package com.bitchat.nostr.util

import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock

class ConcurrentMap<K, V> {
    private val map = mutableMapOf<K, V>()
    private val lock = ReentrantLock()

    val size
        get() = lock.withLock { map.size }

    operator fun get(key: K): V? = lock.withLock {
        map[key]
    }

    fun getAll(): Map<K, V> = lock.withLock { map.toMutableMap() }

    operator fun set(key: K, value: V) = lock.withLock {
        map.put(key, value)
    }

    fun clear() = lock.withLock { map.clear() }

    fun containsKey(key: K): Boolean = lock.withLock { map.containsKey(key) }

    fun remove(key: K): V? = lock.withLock { map.remove(key) }

    fun getOrPut(key: K, defaultValue: () -> V): V = lock.withLock {
        map.getOrPut(key, defaultValue)
    }

    fun filter(predicate: (Map.Entry<K, V>) -> Boolean): Map<K, V> = lock.withLock {
        map.filter(predicate)
    }
}
