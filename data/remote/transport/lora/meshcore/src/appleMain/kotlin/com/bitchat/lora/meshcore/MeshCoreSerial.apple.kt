package com.bitchat.lora.meshcore

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Apple (iOS/macOS) stub implementation of MeshCore serial.
 *
 * MeshCore is currently only supported on embedded Linux (Orange Pi).
 * This stub allows the iOS/macOS apps to compile but won't work at runtime.
 *
 * Future: Could support TCP connection to remote meshcore-pi.
 */
actual class MeshCoreSerial actual constructor() {

    actual val incoming: Flow<ByteArray> = emptyFlow()

    actual val isConnected: Boolean = false

    actual var onDisconnect: (() -> Unit)? = null

    actual fun open(): Boolean {
        println("MeshCore is not supported on Apple platforms")
        return false
    }

    actual fun close() {
        // No-op
    }

    actual fun send(data: ByteArray): Boolean {
        println("MeshCore is not supported on Apple platforms")
        return false
    }
}
