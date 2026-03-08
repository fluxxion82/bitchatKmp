package com.bitchat.local.service

class IosSettingsService : SettingsService {
    override fun isBatteryOptimizationSupported(): Boolean {
        return false
    }

    override fun isBatteryOptimizationDisabled(): Boolean {
        return false
    }

    override fun disableBatteryOptimization() {
        // No-op: iOS handles background operation via UIBackgroundModes
    }
}
