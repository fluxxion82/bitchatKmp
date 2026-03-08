package com.bitchat.local.service

/**
 * Linux implementation of SettingsService.
 *
 * Linux (especially embedded) doesn't have the same battery optimization
 * concepts as mobile platforms. This provides stub implementations.
 */
class LinuxSettingsService : SettingsService {
    override fun isBatteryOptimizationSupported(): Boolean {
        // Linux embedded devices typically don't have battery optimization
        return false
    }

    override fun isBatteryOptimizationDisabled(): Boolean {
        // N/A for Linux
        return true
    }

    override fun disableBatteryOptimization() {
        // No-op: Linux doesn't have battery optimization like mobile
    }
}
