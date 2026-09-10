package com.bitchat.viewvo.settings

import com.bitchat.domain.lora.model.LoRaProtocolType
import com.bitchat.domain.lora.model.LoRaRegion
import com.bitchat.domain.lora.model.LoRaTxPower
import com.bitchat.domain.tor.model.TorAvailability
import com.bitchat.domain.tor.model.TorMode

data class SettingsState(
    val appVersion: String = "1.0.0",
    val selectedTheme: ThemePreference = ThemePreference.SYSTEM,
    val showBackgroundModeSetting: Boolean = false,
    val backgroundModeEnabled: Boolean = false,
    val proofOfWorkEnabled: Boolean = false,
    val powDifficulty: Int = 16,
    /**
     * Why the Tor switch is usable or not. Starts unavailable and stays that way until the
     * settings viewmodel has read the real reason: the switch must never be offered on the
     * optimistic assumption that Tor works here.
     */
    val torAvailability: TorAvailability = TorAvailability.NATIVE_LIBRARY_MISSING,
    /**
     * What the user asked for, not what Tor managed to do. Single source for the switch: it used
     * to be a stored flag written from three places -- the initial load, the mode observer and an
     * optimistic update in the toggle handler -- which could race and leave the switch showing the
     * opposite of the user's choice with no further emission to correct it.
     */
    val requestedTorMode: TorMode = TorMode.OFF,
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

    /**
     * Derived, never assigned. Qualified by availability so a stored ON is not displayed as
     * protection on a platform whose HTTP engine cannot route through a proxy at all.
     */
    val torNetworkEnabled: Boolean
        get() = requestedTorMode == TorMode.ON && torAvailability != TorAvailability.NO_PROXY_SUPPORT
}

enum class ThemePreference {
    SYSTEM,
    LIGHT,
    DARK
}
