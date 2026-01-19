package com.bitchat.bluetooth.protocol

import android.util.Log

actual fun logError(tag: String, message: String) {
    Log.e(tag, message)
}

actual fun logDebug(tag: String, message: String) {
    Log.d(tag, message)
}

actual fun logInfo(tag: String, message: String) {
    Log.i(tag, message)
}
