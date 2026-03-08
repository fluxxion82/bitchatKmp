package com.bitchat.design.util

import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock

actual object VoiceWaveformCache {
    private val cache = mutableMapOf<String, FloatArray>()
    private val lock = ReentrantLock()

    actual fun put(path: String, samples: FloatArray) {
        lock.withLock {
            cache[path] = samples
        }
    }

    actual fun get(path: String): FloatArray? {
        return lock.withLock {
            cache[path]
        }
    }
}
