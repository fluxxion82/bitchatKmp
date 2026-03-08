package com.bitchat.lora.meshtastic

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Apple stub implementation - Meshtastic USB serial not supported on iOS/macOS.
 *
 * iOS doesn't support USB host mode for serial communication.
 * macOS could potentially support it, but is not implemented yet.
 */
actual class MeshtasticSerial {

    actual fun open(): Boolean {
        println("⚠️ Meshtastic serial not supported on Apple platforms")
        return false
    }

    actual fun close() {
        // No-op
    }

    actual fun send(data: ByteArray): Boolean {
        return false
    }

    actual val incoming: Flow<ByteArray> = emptyFlow()

    actual val isConnected: Boolean = false

    /**
     * Callback invoked when connection is lost unexpectedly.
     * Not used on Apple platforms (no connection support).
     */
    actual var onDisconnect: (() -> Unit)? = null
}
