package com.bitchat.local.prefs

import com.bitchat.domain.lora.model.LoRaProtocolType
import com.bitchat.domain.lora.model.LoRaRegion
import com.bitchat.domain.lora.model.LoRaTxPower

interface LoRaPreferences {
    fun isLoRaEnabled(): Boolean
    fun setLoRaEnabled(enabled: Boolean)
    fun getLoRaRegion(): LoRaRegion
    fun setLoRaRegion(region: LoRaRegion)
    fun getTxPower(): LoRaTxPower
    fun setTxPower(power: LoRaTxPower)
    fun isShowLoRaPeersEnabled(): Boolean
    fun setShowLoRaPeersEnabled(enabled: Boolean)
    fun getLoRaProtocol(): LoRaProtocolType
    fun setLoRaProtocol(protocol: LoRaProtocolType)
}
