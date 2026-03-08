package com.bitchat.lora.bitchat.logging

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLog

actual object LoRaLogger {
    @kotlin.concurrent.Volatile
    var enabled: Boolean = true

    private val dateFormatter = NSDateFormatter().apply {
        dateFormat = "HH:mm:ss.SSS"
    }

    actual fun d(tag: String, message: String) {
        if (enabled) NSLog("[D/$tag] %@", message)
    }

    actual fun i(tag: String, message: String) {
        if (enabled) NSLog("[I/$tag] %@", message)
    }

    actual fun w(tag: String, message: String) {
        if (enabled) NSLog("[W/$tag] %@", message)
    }

    actual fun e(tag: String, message: String, throwable: Throwable?) {
        if (enabled) {
            if (throwable != null) {
                NSLog("[E/$tag] %@ - %@", message, throwable.message ?: "Unknown error")
            } else {
                NSLog("[E/$tag] %@", message)
            }
        }
    }

    actual fun v(tag: String, message: String) {
        if (enabled) NSLog("[V/$tag] %@", message)
    }
}
