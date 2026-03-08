package com.bitchat.lora.bitchat.logging

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.gettimeofday
import platform.posix.timeval
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr

@OptIn(ExperimentalForeignApi::class)
actual object LoRaLogger {
    @kotlin.concurrent.Volatile
    var enabled: Boolean = true

    actual fun d(tag: String, message: String) {
        if (enabled) log("D", tag, message)
    }

    actual fun i(tag: String, message: String) {
        if (enabled) log("I", tag, message)
    }

    actual fun w(tag: String, message: String) {
        if (enabled) log("W", tag, message)
    }

    actual fun e(tag: String, message: String, throwable: Throwable?) {
        if (enabled) {
            log("E", tag, message)
            throwable?.let { println("  Exception: ${it.message}") }
        }
    }

    actual fun v(tag: String, message: String) {
        if (enabled) log("V", tag, message)
    }

    private fun log(level: String, tag: String, message: String) {
        val timestamp = getTimestamp()
        println("$timestamp [$level/$tag] $message")
    }

    private fun getTimestamp(): String {
        return memScoped {
            val tv = alloc<timeval>()
            gettimeofday(tv.ptr, null)
            val sec = tv.tv_sec
            val usec = tv.tv_usec
            val ms = usec / 1000
            "${sec % 86400 / 3600}:${sec % 3600 / 60}:${sec % 60}.${ms.toString().padStart(3, '0')}"
        }
    }
}
