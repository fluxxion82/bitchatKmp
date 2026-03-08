package com.bitchat.bluetooth.protocol

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.fflush
import platform.posix.stdout

/**
 * Linux implementation of logging.
 * Uses println for simple console output on embedded devices.
 * Flushes stdout to ensure logs appear immediately.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun logError(tag: String, message: String) {
    println("[$tag] ERROR: $message")
    fflush(stdout)
}

@OptIn(ExperimentalForeignApi::class)
actual fun logDebug(tag: String, message: String) {
    println("[$tag] DEBUG: $message")
    fflush(stdout)
}

@OptIn(ExperimentalForeignApi::class)
actual fun logInfo(tag: String, message: String) {
    println("[$tag] INFO: $message")
    fflush(stdout)
}
