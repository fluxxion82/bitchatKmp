package com.bitchat.lora.bitchat.radio

import com.bitchat.lora.radio.LoRaConfig
import com.bitchat.lora.radio.LoRaEvent
import kotlinx.coroutines.flow.Flow

/**
 * Platform-specific LoRa radio interface.
 *
 * Implementations:
 * - JVM: Serial port via jSerialComm (DSD Tech dongle with AT commands)
 * - Android: USB OTG via usb-serial-for-android
 * - Linux ARM64: SPI via cinterop (RFM95W/SX1276 direct register access)
 * - Apple: Stub (no LoRa hardware support on iOS/macOS)
 */
expect class LoRaRadio {
    /**
     * Configure the radio with the given parameters.
     *
     * @param config Radio configuration
     * @return true if configuration succeeded
     */
    fun configure(config: LoRaConfig): Boolean

    /**
     * Send raw data over LoRa.
     *
     * @param data Bytes to transmit (should be a serialized LoRaFrame)
     * @return true if transmission was initiated successfully
     */
    fun send(data: ByteArray): Boolean

    /**
     * Start receiving packets. Received data will be emitted via [events].
     */
    fun startReceiving()

    /**
     * Stop receiving packets.
     */
    fun stopReceiving()

    /**
     * Close the radio and release resources.
     */
    fun close()

    /**
     * Flow of events from the radio (packets received, errors, etc.)
     */
    val events: Flow<LoRaEvent>

    /**
     * Whether the radio is configured and ready.
     */
    val isReady: Boolean
}
