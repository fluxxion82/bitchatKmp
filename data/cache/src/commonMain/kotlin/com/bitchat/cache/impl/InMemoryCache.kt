package com.bitchat.cache.impl

import com.bitchat.cache.Cache
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update

open class InMemoryCache<in Key : Any, T> : Cache<Key, T> {
    private val ref = atomic<Map<Key, T>>(emptyMap())

    override operator fun get(key: Key): T? = ref.value[key]

    override operator fun set(key: Key, value: T) {
        ref.update { it.toMutableMap().apply { put(key, value) } }
    }

    override fun getAll(): Map<in Key, T> = ref.value

    override fun remove(key: Key) {
        ref.update { it.toMutableMap().apply { remove(key) } }
    }

    override fun getAllValues(): List<T> = ref.value.values.toList()

    override fun removeAll() {
        ref.value = emptyMap()
    }
}
