package com.bitchat.design.util

import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock

actual object VoiceWaveformCache {
    private val lock = ReentrantLock()
    private val map = mutableMapOf<String, FloatArray>()

    actual fun put(path: String, samples: FloatArray) {
        lock.withLock {
            map[path] = samples
        }
    }

    actual fun get(path: String): FloatArray? {
        return lock.withLock {
            map[path]
        }
    }
}
