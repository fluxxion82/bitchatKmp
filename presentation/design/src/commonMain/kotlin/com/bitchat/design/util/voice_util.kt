package com.bitchat.design.util

expect object VoiceWaveformCache {
    fun put(path: String, samples: FloatArray)
    fun get(path: String): FloatArray?
}
