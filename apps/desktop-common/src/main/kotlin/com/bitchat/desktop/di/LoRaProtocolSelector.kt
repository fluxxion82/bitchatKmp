package com.bitchat.desktop.di

import com.bitchat.lora.LoRaProtocolType
import java.util.prefs.Preferences

/**
 * Reads the preferred LoRa protocol from Java Preferences.
 *
 * Used to determine the initial protocol for LoRaProtocolManager
 * when the app starts.
 */
object LoRaProtocolSelector {

    private const val PREFS_NODE = "lora_settings"
    private const val KEY_PROTOCOL = "lora_protocol"
    private const val PROTOCOL_BITCHAT = "BITCHAT"
    private const val PROTOCOL_MESHTASTIC = "MESHTASTIC"

    /**
     * Get the preferred LoRa protocol type from saved preferences.
     *
     * @return The user's preferred protocol type (default: BITCHAT)
     */
    fun getPreferredProtocol(): LoRaProtocolType {
        val prefs = Preferences.userRoot().node(PREFS_NODE)
        val protocol = prefs.get(KEY_PROTOCOL, PROTOCOL_BITCHAT)

        return when (protocol) {
            PROTOCOL_MESHTASTIC -> {
                println("📡 Initial LoRa protocol: Meshtastic")
                LoRaProtocolType.MESHTASTIC
            }
            else -> {
                println("📡 Initial LoRa protocol: BitChat")
                LoRaProtocolType.BITCHAT
            }
        }
    }
}
