package com.bitchat.lora

import com.bitchat.lora.radio.LoRaConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest

/**
 * Manager for switching between LoRa protocol implementations at runtime.
 *
 * Wraps both BitChat and Meshtastic protocols and delegates to the active one.
 * Supports switching protocols on-the-fly without app restart.
 *
 * Usage:
 * ```
 * val manager = LoRaProtocolManager(bitChatProtocol, meshtasticProtocol)
 * manager.start(config) // Starts the active protocol
 *
 * // Switch to Meshtastic
 * manager.switchProtocol(LoRaProtocolType.MESHTASTIC, config)
 * ```
 */
class LoRaProtocolManager(
    private val bitChatProtocol: Lazy<LoRaProtocol>,
    private val meshtasticProtocol: Lazy<LoRaProtocol>,
    private val meshcoreProtocol: Lazy<LoRaProtocol>
) : LoRaProtocol {

    private val _activeType = MutableStateFlow(LoRaProtocolType.BITCHAT)

    /**
     * The currently active protocol type.
     */
    val activeType: StateFlow<LoRaProtocolType> = _activeType.asStateFlow()

    /**
     * The currently active protocol instance.
     */
    private val active: LoRaProtocol
        get() = when (_activeType.value) {
            LoRaProtocolType.BITCHAT -> bitChatProtocol.value
            LoRaProtocolType.MESHTASTIC -> meshtasticProtocol.value
            LoRaProtocolType.MESHCORE -> meshcoreProtocol.value
        }

    /**
     * Switch to a different protocol implementation.
     *
     * Stops the current protocol and starts the new one.
     *
     * @param type The protocol type to switch to
     * @param config Radio configuration for the new protocol
     * @return true if the new protocol started successfully
     */
    suspend fun switchProtocol(type: LoRaProtocolType, config: LoRaConfig = LoRaConfig.US_915): Boolean {
        if (type == _activeType.value) {
            println("📡 LoRaProtocolManager: Already using ${type.name}")
            return active.isReady || active.start(config)
        }

        println("📡 LoRaProtocolManager: Switching from ${_activeType.value} to $type")

        // Stop current protocol
        try {
            active.stop()
            println("📡 LoRaProtocolManager: Stopped ${_activeType.value}")
        } catch (e: Exception) {
            println("⚠️ LoRaProtocolManager: Error stopping ${_activeType.value}: ${e.message}")
        }

        // Switch to new protocol
        _activeType.value = type

        // Start new protocol
        return try {
            val started = active.start(config)
            if (started) {
                println("✅ LoRaProtocolManager: Started $type successfully")
            } else {
                println("❌ LoRaProtocolManager: Failed to start $type")
            }
            started
        } catch (e: Exception) {
            println("❌ LoRaProtocolManager: Error starting $type: ${e.message}")
            false
        }
    }

    /**
     * Set the active protocol type without starting it.
     *
     * Used during initialization to set the preferred protocol from settings.
     */
    fun setActiveType(type: LoRaProtocolType) {
        _activeType.value = type
    }

    // LoRaProtocol interface delegation

    override val peers: StateFlow<List<LoRaPeer>>
        get() = active.peers

    override val incomingMessages: Flow<ByteArray>
        get() = active.incomingMessages

    override val isReady: Boolean
        get() = active.isReady

    override val protocolName: String
        get() = active.protocolName

    override var deviceId: String
        get() = active.deviceId
        set(value) { active.deviceId = value }

    override var nickname: String
        get() = active.nickname
        set(value) { active.nickname = value }

    override suspend fun start(config: LoRaConfig): Boolean {
        println("📡 LoRaProtocolManager: Starting ${_activeType.value}")
        return active.start(config)
    }

    override fun stop() {
        println("📡 LoRaProtocolManager: Stopping ${_activeType.value}")
        active.stop()
    }

    override suspend fun send(data: ByteArray): Boolean {
        println("📡 LoRaProtocolManager.send(): activeType=${_activeType.value}, protocol=${active.protocolName}, isReady=${active.isReady}")
        return active.send(data)
    }
}
