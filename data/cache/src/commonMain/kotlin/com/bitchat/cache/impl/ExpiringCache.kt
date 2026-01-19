package com.bitchat.cache.impl

import com.bitchat.cache.Cache
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

const val ONE_HOUR = 3600L * 1000L
const val TWENTY_FOUR_HOURS = ONE_HOUR * 24

@OptIn(ExperimentalTime::class)
class ExpiringCache<in Key : Any, T>(
    private val cache: Cache<Key, T>,
    private val expiration: Long = ONE_HOUR,
) : Cache<Key, T> by cache {

    private val expirationTimes = mutableMapOf<Key, Long>()

    override operator fun get(key: Key): T? {
        return expirationTimes[key]?.let { expirationTime ->
            val currentTime = Clock.System.now().toEpochMilliseconds()
            when {
                expirationTime > currentTime -> {
                    val current = cache[key]
                    if (current != null) {
                        set(key, current)
                    }
                    current
                }

                else -> {
                    remove(key)
                    null
                }
            }
        }
    }

    override operator fun set(key: Key, value: T) {
        expirationTimes[key] = Clock.System.now().toEpochMilliseconds() + expiration
        cache[key] = value
    }

    override fun getAllValues(): List<T> = cache.getAllValues()

    override fun getAll(): Map<in Key, T> = cache.getAll()

    override fun removeAll() {
        cache.removeAll()
        expirationTimes.clear()
    }

    override fun remove(key: Key) {
        expirationTimes.remove(key)
        cache.remove(key)
    }
}
