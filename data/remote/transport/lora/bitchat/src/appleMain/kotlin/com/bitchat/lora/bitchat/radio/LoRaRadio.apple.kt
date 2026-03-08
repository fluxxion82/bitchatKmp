package com.bitchat.lora.bitchat.radio

import com.bitchat.lora.bitchat.logging.LoRaLogger
import com.bitchat.lora.bitchat.logging.LoRaTags
import com.bitchat.lora.radio.LoRaConfig
import com.bitchat.lora.radio.LoRaEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Apple (iOS/macOS) LoRa radio stub implementation.
 *
 * iOS and macOS do not support LoRa hardware directly.
 * This stub allows the code to compile but always reports no hardware available.
 */
actual class LoRaRadio {
    private val _events = MutableSharedFlow<LoRaEvent>(extraBufferCapacity = 64)
    actual val events: Flow<LoRaEvent> = _events.asSharedFlow()

    actual val isReady: Boolean = false

    actual fun configure(config: LoRaConfig): Boolean {
        LoRaLogger.w(LoRaTags.RADIO, "LoRa hardware not supported on this platform")
        return false
    }

    actual fun send(data: ByteArray): Boolean {
        LoRaLogger.w(LoRaTags.RADIO, "LoRa hardware not supported on this platform")
        return false
    }

    actual fun startReceiving() {
        LoRaLogger.w(LoRaTags.RADIO, "LoRa hardware not supported on this platform")
    }

    actual fun stopReceiving() {
        // No-op
    }

    actual fun close() {
        // No-op
    }
}
