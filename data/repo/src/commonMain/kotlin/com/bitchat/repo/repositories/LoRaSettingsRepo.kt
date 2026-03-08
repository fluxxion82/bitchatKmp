package com.bitchat.repo.repositories

import com.bitchat.domain.lora.model.LoRaProtocolType
import com.bitchat.domain.lora.model.LoRaRegion
import com.bitchat.domain.lora.model.LoRaSettings
import com.bitchat.domain.lora.model.LoRaTxPower
import com.bitchat.domain.lora.repository.LoRaSettingsRepository
import com.bitchat.local.prefs.LoRaPreferences

class LoRaSettingsRepo(
    private val loraPreferences: LoRaPreferences,
) : LoRaSettingsRepository {

    override fun getLoRaSettings(): LoRaSettings {
        return LoRaSettings(
            enabled = loraPreferences.isLoRaEnabled(),
            region = loraPreferences.getLoRaRegion(),
            txPower = loraPreferences.getTxPower(),
            showPeers = loraPreferences.isShowLoRaPeersEnabled(),
            protocol = loraPreferences.getLoRaProtocol()
        )
    }

    override fun setLoRaEnabled(enabled: Boolean) {
        loraPreferences.setLoRaEnabled(enabled)
    }

    override fun setLoRaRegion(region: LoRaRegion) {
        loraPreferences.setLoRaRegion(region)
    }

    override fun setLoRaTxPower(power: LoRaTxPower) {
        loraPreferences.setTxPower(power)
    }

    override fun setShowLoRaPeers(show: Boolean) {
        loraPreferences.setShowLoRaPeersEnabled(show)
    }

    override fun setLoRaProtocol(protocol: LoRaProtocolType) {
        loraPreferences.setLoRaProtocol(protocol)
    }
}
