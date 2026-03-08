package com.bitchat.lora.bitchat.logging

import android.util.Log

actual object LoRaLogger {
    @Volatile
    var enabled: Boolean = true

    actual fun d(tag: String, message: String) {
        if (enabled) Log.d(tag, message)
    }

    actual fun i(tag: String, message: String) {
        if (enabled) Log.i(tag, message)
    }

    actual fun w(tag: String, message: String) {
        if (enabled) Log.w(tag, message)
    }

    actual fun e(tag: String, message: String, throwable: Throwable?) {
        if (enabled) {
            if (throwable != null) {
                Log.e(tag, message, throwable)
            } else {
                Log.e(tag, message)
            }
        }
    }

    actual fun v(tag: String, message: String) {
        if (enabled) Log.v(tag, message)
    }
}
