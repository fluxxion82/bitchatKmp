package com.bitchat.repo.utils

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Keeps peer names learned from traffic bounded while preserving names chosen by the user.
 * Traffic is untrusted and must never displace a conversation the user deliberately opened.
 */
internal class LearnedNames(private val capacity: Int = DEFAULT_CAPACITY) {
    init {
        require(capacity > 0) { "capacity must be positive, was $capacity" }
    }

    private val learned = LinkedHashMap<String, String>()
    private val remembered = mutableMapOf<String, String>()
    private val lock = SynchronizedObject()

    fun learn(peerID: String, name: String) = synchronized(lock) {
        if (name.isBlank()) return@synchronized
        if (learned.containsKey(peerID)) {
            learned[peerID] = name
            return@synchronized
        }
        while (learned.size >= capacity) {
            learned.entries.iterator().run { next(); remove() }
        }
        learned[peerID] = name
    }

    fun remember(peerID: String, name: String) = synchronized(lock) {
        if (name.isNotBlank()) remembered[peerID] = name
    }

    operator fun get(peerID: String): String? = synchronized(lock) {
        remembered[peerID] ?: learned[peerID]
    }

    fun clear() = synchronized(lock) {
        learned.clear()
        remembered.clear()
    }

    companion object {
        const val DEFAULT_CAPACITY = 1337
    }
}
