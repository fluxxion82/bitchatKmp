package com.bitchat.cache.impl

import com.bitchat.cache.Cache

class ClearingCache<in Key : Any, T>(
    private val cache: Cache<Key, T>,
) : Cache<Key, T> by cache {

    override operator fun get(key: Key): T? {
        return cache[key].also { remove(key) }
    }

    override operator fun set(key: Key, value: T) {
        cache[key] = value
    }

    override fun getAllValues(): List<T> = cache.getAllValues().also { removeAll() }

    override fun getAll(): Map<in Key, T> = cache.getAll().also { removeAll() }

    override fun removeAll() {
        cache.removeAll()
    }

    override fun remove(key: Key) {
        cache.remove(key)
    }
}
