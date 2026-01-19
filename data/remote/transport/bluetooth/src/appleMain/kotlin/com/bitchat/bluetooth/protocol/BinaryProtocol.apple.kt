package com.bitchat.bluetooth.protocol

import platform.Foundation.NSLog

/**
 * iOS implementation of logging
 * Uses NSLog with proper log level prefixes for filtering
 *
 * Note: Could be upgraded to os_log in the future for better performance,
 * but NSLog is simpler and more compatible with Kotlin/Native
 */
actual fun logError(tag: String, message: String) {
    NSLog("[$tag] ERROR: $message")
}

actual fun logDebug(tag: String, message: String) {
    NSLog("[$tag] DEBUG: $message")
}

actual fun logInfo(tag: String, message: String) {
    NSLog("[$tag] INFO: $message")
}
