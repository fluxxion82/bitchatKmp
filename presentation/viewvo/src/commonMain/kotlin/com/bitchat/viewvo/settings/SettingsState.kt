package com.bitchat.viewvo.settings

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
    val torLastLogLine: String = ""
)

enum class ThemePreference {
    SYSTEM,
    LIGHT,
    DARK
}
