package com.bitchat.local.prefs.impl

import com.bitchat.domain.lora.model.LoRaProtocolType
import com.bitchat.domain.lora.model.LoRaRegion
import com.bitchat.domain.lora.model.LoRaTxPower
import com.bitchat.local.prefs.LoRaPreferences
import com.russhwolf.settings.Settings

internal class LocalLoRaPreferences(
    settingsFactory: Settings.Factory
) : LoRaPreferences {
    private val settings = settingsFactory.create(PREFS_NAME)

    override fun isLoRaEnabled(): Boolean {
        return settings.getBoolean(KEY_ENABLED, true)
    }

    override fun setLoRaEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_ENABLED, enabled)
    }

    override fun getLoRaRegion(): LoRaRegion {
        val regionName = settings.getString(KEY_REGION, LoRaRegion.US_915.name)
        return try {
            LoRaRegion.valueOf(regionName)
        } catch (e: IllegalArgumentException) {
            LoRaRegion.US_915
        }
    }

    override fun setLoRaRegion(region: LoRaRegion) {
        settings.putString(KEY_REGION, region.name)
    }

    override fun getTxPower(): LoRaTxPower {
        val powerName = settings.getString(KEY_TX_POWER, LoRaTxPower.MEDIUM.name)
        return try {
            LoRaTxPower.valueOf(powerName)
        } catch (e: IllegalArgumentException) {
            LoRaTxPower.MEDIUM
        }
    }

    override fun setTxPower(power: LoRaTxPower) {
        settings.putString(KEY_TX_POWER, power.name)
    }

    override fun isShowLoRaPeersEnabled(): Boolean {
        return settings.getBoolean(KEY_SHOW_PEERS, true)
    }

    override fun setShowLoRaPeersEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_SHOW_PEERS, enabled)
    }

    override fun getLoRaProtocol(): LoRaProtocolType {
        val protocolName = settings.getString(KEY_PROTOCOL, LoRaProtocolType.BITCHAT.name)
        return try {
            LoRaProtocolType.valueOf(protocolName)
        } catch (e: IllegalArgumentException) {
            LoRaProtocolType.BITCHAT
        }
    }

    override fun setLoRaProtocol(protocol: LoRaProtocolType) {
        settings.putString(KEY_PROTOCOL, protocol.name)
    }

    companion object {
        private const val PREFS_NAME = "lora_settings"
        private const val KEY_ENABLED = "lora_enabled"
        private const val KEY_REGION = "lora_region"
        private const val KEY_TX_POWER = "lora_tx_power"
        private const val KEY_SHOW_PEERS = "lora_show_peers"
        private const val KEY_PROTOCOL = "lora_protocol"
    }
}
