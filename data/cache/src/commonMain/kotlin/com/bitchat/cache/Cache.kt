package com.bitchat.cache

interface Cache<in Key : Any, T> {
    operator fun get(key: Key): T?
    operator fun set(key: Key, value: T)
    fun getAll(): Map<in Key, T>
    fun getAllValues(): List<T>
    fun remove(key: Key)
    fun removeAll()
}
