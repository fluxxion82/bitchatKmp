package com.bitchat.viewvo.settings

import com.bitchat.domain.lora.model.LoRaProtocolType
import com.bitchat.domain.lora.model.LoRaRegion
import com.bitchat.domain.lora.model.LoRaTxPower

data class SettingsState(
    val appVersion: String = "1.0.0",
    val selectedTheme: ThemePreference = ThemePreference.SYSTEM,
    val showBackgroundModeSetting: Boolean = false,
    val backgroundModeEnabled: Boolean = false,
    val proofOfWorkEnabled: Boolean = false,
    val torNetworkEnabled: Boolean = false,
    val powDifficulty: Int = 16,
    val torAvailable: Boolean = false,
    val torRunning: Boolean = false,
    val torBootstrapPercent: Int = 0,
    val torLastLogLine: String = "",
    // LoRa settings
    val loraAvailable: Boolean = false,
    val loraEnabled: Boolean = true,
    val loraRegion: LoRaRegion = LoRaRegion.US_915,
    val loraTxPower: LoRaTxPower = LoRaTxPower.MEDIUM,
    val loraShowPeers: Boolean = true,
    val loraProtocol: LoRaProtocolType = LoRaProtocolType.BITCHAT
)

enum class ThemePreference {
    SYSTEM,
    LIGHT,
    DARK
}
