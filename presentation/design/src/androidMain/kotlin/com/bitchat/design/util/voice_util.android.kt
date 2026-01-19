package com.bitchat.design.util

import java.util.concurrent.ConcurrentHashMap

actual object VoiceWaveformCache {
    private val map = ConcurrentHashMap<String, FloatArray>()

    actual fun put(path: String, samples: FloatArray) {
        map[path] = samples
    }

    actual fun get(path: String): FloatArray? = map[path]
}
