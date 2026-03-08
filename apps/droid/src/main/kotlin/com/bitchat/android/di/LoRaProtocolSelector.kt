package com.bitchat.android.di

import android.content.Context
import com.bitchat.lora.LoRaProtocolType

/**
 * Reads the preferred LoRa protocol from SharedPreferences.
 *
 * Used to determine the initial protocol for LoRaProtocolManager
 * when the app starts.
 */
object LoRaProtocolSelector {

    private const val PREFS_NAME = "lora_settings"
    private const val KEY_PROTOCOL = "lora_protocol"
    private const val PROTOCOL_BITCHAT = "BITCHAT"
    private const val PROTOCOL_MESHTASTIC = "MESHTASTIC"

    /**
     * Get the preferred LoRa protocol type from saved preferences.
     *
     * @param context Application context
     * @return The user's preferred protocol type (default: BITCHAT)
     */
    fun getPreferredProtocol(context: Context): LoRaProtocolType {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val protocol = prefs.getString(KEY_PROTOCOL, PROTOCOL_BITCHAT) ?: PROTOCOL_BITCHAT

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
