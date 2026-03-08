package com.bitchat.lora

import com.bitchat.lora.radio.LoRaConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Instant

/**
 * Common interface for LoRa protocol implementations.
 *
 * This interface abstracts the LoRa transport layer, allowing different
 * protocol implementations (BitChat native, Meshtastic, etc.) to be
 * used interchangeably.
 *
 * Implementations are responsible for:
 * - Sending and receiving messages
 * - Tracking discovered peers via heartbeats or protocol-specific discovery
 * - Managing the radio lifecycle
 */
interface LoRaProtocol {

    /**
     * Flow of currently discovered peers on the LoRa network.
     *
     * Peers are discovered via protocol-specific mechanisms:
     * - BitChat: Heartbeat broadcasts every 60 seconds
     * - Meshtastic: NodeDB from the device
     *
     * Peers are removed from the list after not being seen for a timeout period
     * (typically 3 minutes for BitChat).
     */
    val peers: StateFlow<List<LoRaPeer>>

    /**
     * Flow of complete reassembled incoming messages.
     *
     * For fragmented protocols, this emits only after all fragments
     * have been received and reassembled.
     */
    val incomingMessages: Flow<ByteArray>

    /**
     * Whether the protocol transport is ready to send/receive.
     */
    val isReady: Boolean

    /**
     * Human-readable name of this protocol implementation.
     *
     * Examples: "BitChat", "Meshtastic"
     */
    val protocolName: String

    /**
     * The current user's device ID used in peer discovery.
     */
    var deviceId: String

    /**
     * The current user's nickname for heartbeat broadcasts.
     */
    var nickname: String

    /**
     * Start the protocol transport with the given configuration.
     *
     * This configures the radio, starts receiving, and begins
     * protocol-specific background tasks (like heartbeat broadcasting).
     *
     * @param config Radio configuration (frequency, spreading factor, etc.)
     * @return true if started successfully
     */
    suspend fun start(config: LoRaConfig = LoRaConfig.US_915): Boolean

    /**
     * Stop the protocol transport and release resources.
     *
     * Stops the radio, cancels background tasks, and clears state.
     */
    fun stop()

    /**
     * Send a message over LoRa.
     *
     * Large messages will be automatically fragmented if the protocol
     * supports fragmentation.
     *
     * @param data Raw message bytes to send
     * @return true if the message was sent successfully (or all fragments sent)
     */
    suspend fun send(data: ByteArray): Boolean
}

/**
 * Represents a discovered peer on the LoRa network.
 *
 * Peers are discovered via protocol-specific mechanisms and tracked
 * with their last seen timestamp for timeout-based cleanup.
 */
data class LoRaPeer(
    /**
     * Unique device identifier.
     *
     * For BitChat: 8-byte hex string from heartbeat
     * For Meshtastic: Node number (as string)
     */
    val deviceId: String,

    /**
     * User-friendly nickname/display name.
     *
     * For BitChat: Nickname from heartbeat payload
     * For Meshtastic: longName from NodeInfo
     */
    val nickname: String,

    /**
     * Timestamp when this peer was last seen (received a packet from them).
     */
    val lastSeen: Instant,

    /**
     * Received Signal Strength Indicator in dBm.
     *
     * Typical range: -120 dBm (very weak) to -30 dBm (very strong)
     * Useful for estimating distance/link quality.
     */
    val rssi: Int,

    /**
     * Signal-to-Noise Ratio in dB.
     *
     * Higher is better. Typical range: -20 dB to +10 dB
     * SNR > 0 indicates signal is stronger than noise.
     */
    val snr: Float
) {
    /**
     * Whether this peer is considered "online" based on last seen time.
     *
     * @param now Current timestamp
     * @param timeoutSeconds Seconds after which peer is considered offline (default: 180 = 3 minutes)
     * @return true if peer was seen within the timeout period
     */
    fun isOnline(now: Instant, timeoutSeconds: Long = 180): Boolean {
        val elapsed = now.epochSeconds - lastSeen.epochSeconds
        return elapsed < timeoutSeconds
    }
}
