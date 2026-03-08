package com.bitchat.lora.bitchat.logging

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

actual object LoRaLogger {
    @Volatile
    var enabled: Boolean = true

    @Volatile
    var minLevel: LogLevel = LogLevel.DEBUG

    actual fun d(tag: String, message: String) {
        if (enabled && minLevel <= LogLevel.DEBUG) {
            log("D", tag, message)
        }
    }

    actual fun i(tag: String, message: String) {
        if (enabled && minLevel <= LogLevel.INFO) {
            log("I", tag, message)
        }
    }

    actual fun w(tag: String, message: String) {
        if (enabled && minLevel <= LogLevel.WARN) {
            log("W", tag, message)
        }
    }

    actual fun e(tag: String, message: String, throwable: Throwable?) {
        if (enabled && minLevel <= LogLevel.ERROR) {
            log("E", tag, message)
            throwable?.printStackTrace()
        }
    }

    actual fun v(tag: String, message: String) {
        if (enabled && minLevel <= LogLevel.VERBOSE) {
            log("V", tag, message)
        }
    }

    private fun log(level: String, tag: String, message: String) {
        val time = LocalDateTime.now().format(timeFormatter)
        println("$time [$level/$tag] $message")
    }
}

enum class LogLevel {
    VERBOSE, DEBUG, INFO, WARN, ERROR, NONE
}
