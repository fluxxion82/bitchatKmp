package com.bitchat.lora.meshtastic

import kotlinx.coroutines.flow.Flow

/**
 * Platform-specific Meshtastic serial interface.
 *
 * Handles USB serial communication with Meshtastic devices using
 * the Meshtastic protocol framing:
 * - Start bytes: 0x94 0xC3
 * - Varint-encoded length
 * - Protobuf-encoded message
 *
 * Implementations:
 * - Android: USB OTG via usb-serial-for-android
 * - JVM: Serial port via jSerialComm
 * - Apple: Stub (not supported)
 * - Linux ARM64: Stub (typically use BLE or native LoRa)
 */
expect class MeshtasticSerial() {

    /**
     * Open connection to the Meshtastic device.
     *
     * @return true if connection was opened successfully
     */
    fun open(): Boolean

    /**
     * Close the connection to the Meshtastic device.
     */
    fun close()

    /**
     * Send a raw protobuf-encoded ToRadio message.
     *
     * The implementation handles the serial framing
     * (start bytes + varint length + data).
     *
     * @param data Protobuf-encoded ToRadio message
     * @return true if sent successfully
     */
    fun send(data: ByteArray): Boolean

    /**
     * Flow of received FromRadio protobuf data.
     *
     * The implementation handles parsing the serial framing
     * and emits just the protobuf payload.
     */
    val incoming: Flow<ByteArray>

    /**
     * Whether the serial connection is open and ready.
     */
    val isConnected: Boolean

    /**
     * Callback invoked when the connection is lost unexpectedly.
     *
     * This is called when:
     * - The read loop detects connection closed by peer
     * - A write operation fails with EPIPE/broken pipe
     * - Any other unexpected disconnection occurs
     *
     * The protocol layer should use this to trigger reconnection attempts.
     */
    var onDisconnect: (() -> Unit)?

}

/**
 * Meshtastic serial protocol constants.
 */
object MeshtasticSerialConstants {
    /**
     * Meshtastic serial protocol start byte 1.
     */
    const val START_BYTE_1: Int = 0x94

    /**
     * Meshtastic serial protocol start byte 2.
     */
    const val START_BYTE_2: Int = 0xC3
}
