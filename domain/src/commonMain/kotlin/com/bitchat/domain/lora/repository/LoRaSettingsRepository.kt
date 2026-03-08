package com.bitchat.domain.lora.repository

import com.bitchat.domain.lora.model.LoRaProtocolType
import com.bitchat.domain.lora.model.LoRaRegion
import com.bitchat.domain.lora.model.LoRaSettings
import com.bitchat.domain.lora.model.LoRaTxPower

/**
 * Repository for LoRa radio settings.
 */
interface LoRaSettingsRepository {
    fun getLoRaSettings(): LoRaSettings
    fun setLoRaEnabled(enabled: Boolean)
    fun setLoRaRegion(region: LoRaRegion)
    fun setLoRaTxPower(power: LoRaTxPower)
    fun setShowLoRaPeers(show: Boolean)
    fun setLoRaProtocol(protocol: LoRaProtocolType)
}
