package com.bitchat.local.service

class JvmSettingsService : SettingsService {
    override fun isBatteryOptimizationSupported(): Boolean {
        return false
    }

    override fun isBatteryOptimizationDisabled(): Boolean {
        return false
    }

    override fun disableBatteryOptimization() {

    }
}