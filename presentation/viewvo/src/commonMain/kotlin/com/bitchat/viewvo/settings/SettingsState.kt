package com.bitchat.viewvo.settings

import com.bitchat.domain.lora.model.LoRaProtocolType
import com.bitchat.domain.lora.model.LoRaRegion
import com.bitchat.domain.lora.model.LoRaTxPower
import com.bitchat.domain.tor.model.TorAvailability

data class SettingsState(
    val appVersion: String = "1.0.0",
    val selectedTheme: ThemePreference = ThemePreference.SYSTEM,
    val showBackgroundModeSetting: Boolean = false,
    val backgroundModeEnabled: Boolean = false,
    val proofOfWorkEnabled: Boolean = false,
    val torNetworkEnabled: Boolean = false,
    val powDifficulty: Int = 16,
    /**
     * Why the Tor switch is usable or not. Starts unavailable and stays that way until the
     * settings viewmodel has read the real reason: the switch must never be offered on the
     * optimistic assumption that Tor works here.
     */
    val torAvailability: TorAvailability = TorAvailability.NATIVE_LIBRARY_MISSING,
    val torRunning: Boolean = false,
    val torBootstrapPercent: Int = 0,
    val torLastLogLine: String = "",
    /** The actionable reason Tor is not working, e.g. a missing native library. */
    val torErrorMessage: String? = null,
    // LoRa settings
    val loraAvailable: Boolean = false,
    val loraEnabled: Boolean = true,
    val loraRegion: LoRaRegion = LoRaRegion.US_915,
    val loraTxPower: LoRaTxPower = LoRaTxPower.MEDIUM,
    val loraShowPeers: Boolean = true,
    val loraProtocol: LoRaProtocolType = LoRaProtocolType.BITCHAT
) {
    /** Convenience for the many places that only care whether the switch is live. */
    val torAvailable: Boolean get() = torAvailability.isAvailable
}

enum class ThemePreference {
    SYSTEM,
    LIGHT,
    DARK
}
