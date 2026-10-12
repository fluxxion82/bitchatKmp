package com.bitchat.repo.repositories

import com.bitchat.domain.lora.model.LoRaProtocolType
import com.bitchat.domain.lora.model.LoRaRegion
import com.bitchat.domain.lora.model.LoRaSettings
import com.bitchat.domain.lora.model.LoRaTxPower
import com.bitchat.domain.lora.repository.LoRaSettingsRepository
import com.bitchat.local.prefs.LoRaPreferences
import com.bitchat.lora.LoRaProtocolManager

class LoRaSettingsRepo(
    private val loraPreferences: LoRaPreferences,
    // Null where the app has no LoRa stack (iOS).
    private val loraProtocolManager: LoRaProtocolManager? = null,
) : LoRaSettingsRepository {

    override fun getLoRaSettings(): LoRaSettings {
        return LoRaSettings(
            enabled = loraPreferences.isLoRaEnabled(),
            region = loraPreferences.getLoRaRegion(),
            txPower = loraPreferences.getTxPower(),
            showPeers = loraPreferences.isShowLoRaPeersEnabled(),
            protocol = runnableProtocol(),
        )
    }

    // A saved protocol this app cannot run reads as the one the manager falls back to.
    private fun runnableProtocol(): LoRaProtocolType {
        val saved = loraPreferences.getLoRaProtocol()
        val available = getAvailableLoRaProtocols()
        return if (saved in available) saved else available.firstOrNull() ?: saved
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

    override fun getAvailableLoRaProtocols(): List<LoRaProtocolType> {
        val registered = loraProtocolManager?.availableTypes ?: return emptyList()
        return LoRaProtocolType.entries.filter { type -> registered.any { it.name == type.name } }
    }
}
