package com.bitchat.bluetooth.protocol

actual fun logError(tag: String, message: String) {
    System.err.println("[$tag] ERROR: $message")
}

actual fun logDebug(tag: String, message: String) {
    println("[$tag] DEBUG: $message")
}

actual fun logInfo(tag: String, message: String) {
    println("[$tag] INFO: $message")
}
